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
 * Orderful element names: Orderful exposes X12 elements as camelCase names. The ones
 * this script looks for are listed in KEYS below. Confirm them against a real 214 from
 * your Orderful account (Transaction > JSON view) and adjust KEYS only — the rest of the
 * script searches the whole document tree, so segment/loop nesting doesn't matter.
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

final Map KEYS = [
    scac              : 'standardCarrierAlphaCode',      // B10-03
    shipmentId        : 'shipmentIdentificationNumber',  // B10-02
    refQualifier      : 'referenceIdentificationQualifier', // L11-02
    refValue          : 'referenceIdentification',       // L11-01 / B10-01
    statusCode        : 'shipmentStatusCode',            // AT7-01
    statusReason      : 'shipmentStatusOrAppointmentReasonCode', // AT7-02
    date              : 'date',                          // AT7-05 (CCYYMMDD)
    time              : 'time',                          // AT7-06 (HHMM[SS])
    timeCode          : 'timeCode',                      // AT7-07 (LT, ET, CT, MT, PT, UT, ...)
    equipmentInitial  : 'equipmentInitial',              // MS2-01
    equipmentNumber   : 'equipmentNumber',               // MS2-02
    city              : 'cityName',                      // MS1-01
    state             : 'stateOrProvinceCode',           // MS1-02
    country           : 'countryCode',                   // MS1-03
]

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

def firstValue = { root, String key ->
    def found = null
    walk(root) { Map m -> if (found == null && m[key] instanceof String && m[key]) found = m[key] }
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

    // L11 references, keyed by qualifier (BM = bill of lading, CN = PRO, PO, ...)
    Map refs = [:]
    walk(doc) { Map m ->
        if (m[KEYS.refQualifier] && m[KEYS.refValue] && !refs.containsKey(m[KEYS.refQualifier])) {
            refs[m[KEYS.refQualifier]] = m[KEYS.refValue]
        }
    }

    // Every AT7 status in the transaction; keep the latest by event time. The event's
    // location (MS1) lives in the same AT7 loop, i.e. the map that holds the AT7 segment.
    def isStatus = { it instanceof Map && it[KEYS.statusCode] }
    def locationIn = { scope ->
        [firstValue(scope, KEYS.city), firstValue(scope, KEYS.state), firstValue(scope, KEYS.country)]
            .findAll { it }.join(', ') ?: null
    }
    List events = []
    Set seen = Collections.newSetFromMap(new IdentityHashMap())
    def addEvent = { Map m, scope ->
        if (!seen.add(m)) return
        events << [code    : m[KEYS.statusCode],
                   reason  : m[KEYS.statusReason],
                   at      : toIso(m[KEYS.date], m[KEYS.time], m[KEYS.timeCode]),
                   location: scope == null ? null : locationIn(scope)]
    }
    walk(doc) { Map m ->
        m.values().each { v ->
            if (isStatus(v)) addEvent(v, m)
            else if (v instanceof List) v.findAll(isStatus).each { addEvent(it, m) }
        }
    }
    if (isStatus(doc)) addEvent(doc, null)
    def latest = events.findAll { it.at }.max { it.at } ?: (events ? events[-1] : null)

    String equipInit = firstValue(doc, KEYS.equipmentInitial)
    String equipNum  = firstValue(doc, KEYS.equipmentNumber)
    // Containers are usually sent as MS2-01 prefix (e.g. MSCU) + MS2-02 digits; some
    // partners put the full number in MS2-02.
    String container = equipNum && equipInit && !equipNum.startsWith(equipInit) ? equipInit + equipNum : equipNum

    Map canonical = [
        orderfulTransactionId: props.getProperty('document.dynamic.userdefined.orderfulTransactionId') ?: doc?.id?.toString(),
        scac                 : firstValue(doc, KEYS.scac),
        shipmentId           : firstValue(doc, KEYS.shipmentId),
        containerNumber      : sqlSafe(container),
        billOfLading         : sqlSafe(refs['BM'] ?: refs['MB']),
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
        throw new IllegalStateException("214 missing status (AT7) or container/BOL (MS2/L11*BM): " +
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
