/*
 * Boomi Data Process (Custom Scripting, Groovy) — Normalize214
 *
 * Input : one Orderful 214 (Transportation Carrier Shipment Status) JSON document per
 *         document (the Orderful transaction message, with or without the Orderful
 *         envelope around it).
 * Output: one SuiteQL request body per input document, ready to POST to
 *         /services/rest/query/v1/suiteql to find the NetSuite container/shipment.
 *
 * Side effects (read later by BuildNetSuitePatch.groovy):
 *   DPP  TNC_214_CANONICAL   flat JSON with the fields we care about
 *   DDP  document.dynamic.userdefined.containerNumber / statusCode / orderfulTransactionId
 *
 * Run this after a Flow Control shape set to "Run each document individually" — the
 * canonical record is passed downstream in a Dynamic Process Property, which is
 * execution-wide.
 *
 * Orderful segment/element names below are taken from a real Orderful 214 (X12 004010,
 * drayage carrier; anonymized copy in samples/orderful-214.json). The script finds segments by name
 * anywhere in the tree, so it works with or without Orderful's envelope around the
 * transaction and for one or many LX/AT7 loops.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// ---- Configuration -------------------------------------------------------------------

// NetSuite record + field that holds the container number. Native Inbound Shipment by
// default; change if TNC tracks containers in a custom record or a different field.
final String NS_RECORD_TABLE      = 'inboundshipment'
final String NS_CONTAINER_FIELD   = 'externaldocumentnumber'
final String NS_BOL_FIELD         = 'billoflading'
final String NS_LAST_STATUS_FIELD = 'custrecord_tnc_ib_edi_status_dt' // placeholder: last applied 214 event time

// Orderful segment names (X12 segment in comments).
final String SEG_B10 = 'beginningSegmentForTransportationCarrierShipmentStatusMessage'
final String SEG_L11 = 'businessInstructionsAndReferenceNumber'
final String SEG_AT7 = 'shipmentStatusDetails'
final String SEG_MS1 = 'equipmentShipmentOrRealPropertyLocation'
final String SEG_MS2 = 'equipmentOrContainerOwnerAndType'
// AT7-01 shipment status, or AT7-03 appointment status when AT7-01 is empty.
final List AT7_STATUS_ELEMENTS = ['shipmentStatusIndicatorCode', 'shipmentAppointmentStatusCode']

// ---- Helpers -------------------------------------------------------------------------

/** Depth-first walk; calls visitor for every Map in the tree. */
def walk
walk = { node, Closure visitor ->
    if (node instanceof Map) {
        visitor(node)
        node.values().each { walk(it, visitor) }
    } else if (node instanceof List) {
        node.each { walk(it, visitor) }
    }
}

/** Every occurrence of a segment, anywhere in the tree, as [segment: Map, loop: Map] pairs. */
def segments = { root, String name ->
    List found = []
    walk(root) { Map m ->
        def v = m[name]
        (v instanceof List ? v : (v instanceof Map ? [v] : [])).each { if (it instanceof Map) found << [segment: it, loop: m] }
    }
    found
}

def tzOffsets = [LT: null, UT: '+00:00', GM: '+00:00', ET: '-05:00', ED: '-04:00', ES: '-05:00',
                 CT: '-06:00', CD: '-05:00', CS: '-06:00', MT: '-07:00', MD: '-06:00', MS: '-07:00',
                 PT: '-08:00', PD: '-07:00', PS: '-08:00']

/** CCYYMMDD + HHMM[SS] (+ X12 time code) -> ISO-8601 string; null if no date. */
def toIso = { String d, String t, String code ->
    if (!d || !(d ==~ /\d{8}/)) return null
    String hhmmss = (t ?: '0000').padRight(6, '0').take(6)
    String iso = "${d[0..3]}-${d[4..5]}-${d[6..7]}T${hhmmss[0..1]}:${hhmmss[2..3]}:${hhmmss[4..5]}"
    String off = code ? tzOffsets[code.toUpperCase()] : null
    off ? iso + off : iso
}

/** Only allow characters that can appear in a container/BOL number into SuiteQL. */
def sqlSafe = { String v -> v ? v.toUpperCase().replaceAll(/[^A-Z0-9\-]/, '') : null }

def logger = ExecutionUtil.getBaseLogger()

// ---- Main ----------------------------------------------------------------------------

for (int i = 0; i < dataContext.getDataCount(); i++) {
    InputStream is = dataContext.getStream(i)
    Properties props = dataContext.getProperties(i)
    def doc = new JsonSlurper().parseText(new String(is.getBytes(), 'UTF-8'))

    Map b10 = segments(doc, SEG_B10).find()?.segment ?: [:]

    // L11 references, keyed by qualifier (EQ = equipment/container, BM = bill of lading,
    // CN = PRO, PO = purchase order, ...)
    Map refs = [:]
    segments(doc, SEG_L11).each { s ->
        def q = s.segment.referenceIdentificationQualifier
        if (q && s.segment.referenceIdentification && !refs.containsKey(q)) refs[q] = s.segment.referenceIdentification
    }

    // Every AT7 in the transaction; keep the latest by event time. The event's location
    // (MS1) and container (MS2) sit beside it in the same AT7 loop.
    List events = segments(doc, SEG_AT7).collect { s ->
        Map at7 = s.segment
        Map ms1 = segments(s.loop, SEG_MS1).find()?.segment
        Map ms2 = segments(s.loop, SEG_MS2).find()?.segment
        [code     : AT7_STATUS_ELEMENTS.collect { at7[it] }.find { it },
         reason   : at7.shipmentStatusOrAppointmentReasonCode,
         at       : toIso(at7.date, at7.time, at7.timeCode),
         location : ms1 ? [ms1.cityName, ms1.stateOrProvinceCode, ms1.countryCode].findAll { it }.join(', ') : null,
         // MS2-01 owner prefix + MS2-02 number + MS2-03 check digit, e.g. TSTU + 123456 + 0
         container: ms2?.equipmentNumber ? (ms2.equipmentNumber.startsWith(ms2.standardCarrierAlphaCode ?: '~')
                        ? ms2.equipmentNumber
                        : (ms2.standardCarrierAlphaCode ?: '') + ms2.equipmentNumber) + (ms2.equipmentNumberCheckDigit ?: '')
                    : null]
    }.findAll { it.code }
    def latest = events.findAll { it.at }.max { it.at } ?: (events ? events[-1] : null)

    // L11*EQ carries the full container number; MS2 is the fallback.
    String container = refs['EQ'] ?: latest?.container ?: events.find { it.container }?.container

    Map canonical = [
        orderfulTransactionId: props.getProperty('document.dynamic.userdefined.orderfulTransactionId') ?: doc?.id?.toString(),
        scac                 : b10.standardCarrierAlphaCode,
        carrierReference     : b10.referenceIdentification,         // B10-01, carrier's load reference
        shipmentId           : b10.shipmentIdentificationNumber,    // B10-02
        containerNumber      : sqlSafe(container),
        // Ocean BOL: L11*BM/MB when sent, otherwise B10-02 (the drayage carrier sends the
        // ocean bill of lading there, e.g. a ZIM/Maersk BOL).
        billOfLading         : sqlSafe(refs['BM'] ?: refs['MB'] ?: b10.shipmentIdentificationNumber),
        proNumber            : refs['CN'],
        poNumber             : refs['PO'],
        statusCode           : latest?.code,
        statusReason         : latest?.reason,
        statusDateTime       : latest?.at,
        statusDate           : latest?.at ? latest.at.take(10) : null,
        location             : latest?.location,
    ]

    if (!canonical.statusCode || !(canonical.containerNumber || canonical.billOfLading)) {
        // Can't match or apply anything; fail the document so it is visible in Process
        // Reporting and the Orderful delivery is not acknowledged.
        throw new IllegalStateException("214 missing status (AT7) or container/BOL (L11*EQ, MS2, L11*BM, B10-02): " +
                JsonOutput.toJson(canonical))
    }

    List where = []
    if (canonical.containerNumber) where << "UPPER(${NS_CONTAINER_FIELD}) = '${canonical.containerNumber}'"
    if (canonical.billOfLading)    where << "UPPER(${NS_BOL_FIELD}) = '${canonical.billOfLading}'"
    String q = "SELECT id, shipmentstatus, ${NS_LAST_STATUS_FIELD} AS laststatusdt " +
               "FROM ${NS_RECORD_TABLE} WHERE ${where.join(' OR ')}"

    ExecutionUtil.setDynamicProcessProperty('TNC_214_CANONICAL', JsonOutput.toJson(canonical), false)
    props.setProperty('document.dynamic.userdefined.containerNumber', canonical.containerNumber ?: '')
    props.setProperty('document.dynamic.userdefined.statusCode', canonical.statusCode)
    props.setProperty('document.dynamic.userdefined.orderfulTransactionId', canonical.orderfulTransactionId ?: '')
    logger.info("214 ${canonical.statusCode} @ ${canonical.statusDateTime} for container=${canonical.containerNumber} bol=${canonical.billOfLading}")

    byte[] out = JsonOutput.toJson([q: q]).getBytes('UTF-8')
    dataContext.storeStream(new ByteArrayInputStream(out), props)
}
