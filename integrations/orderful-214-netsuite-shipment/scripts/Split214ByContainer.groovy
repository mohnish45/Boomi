/*
 * Boomi Data Process (Custom Scripting, Groovy) — Split214ByContainer
 *
 * Input : Orderful 214 transactions (with or without the Orderful envelope), one per
 *         document. DDP orderfulTransactionId should be set by the step that fetched it.
 * Output: one "container status" JSON document per container per transaction set:
 *   { orderfulTransactionId, bol, containerNumber, scac, carrierReference,
 *     statusCode, statusReason, statusDateTime, statusDate, location, eventCount }
 *   DDPs: bol, containerNumber, orderfulTransactionId — `bol` is the Document Cache key.
 *
 * Containers: a 214 can carry several containers, either as several transaction sets or
 * as several LX/AT7 loops each with its own MS2. Each AT7 event belongs to the container
 * in its own MS2, else the LX loop's L11*EQ, else the only container in the transaction
 * set. Events that still have no container are emitted with containerNumber = null and
 * apply to every inbound shipment under the B10-02 number.
 *
 * Transactions that can't be used (no B10-02, no AT7) are logged and recorded as INVALID
 * in DPP TNC_214_RESULTS (they show up in the emails) instead of failing the whole batch.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// Orderful segment names (X12 segment in comments), confirmed against a real Orderful 214.
final String SEG_B10 = 'beginningSegmentForTransportationCarrierShipmentStatusMessage'
final String SEG_L11 = 'businessInstructionsAndReferenceNumber'
final String SEG_AT7 = 'shipmentStatusDetails'
final String SEG_MS1 = 'equipmentShipmentOrRealPropertyLocation'
final String SEG_MS2 = 'equipmentOrContainerOwnerAndType'
final String LOOP_LX = 'LX_loop'
// AT7-01 shipment status, or AT7-03 appointment status when AT7-01 is empty.
final List AT7_STATUS_ELEMENTS = ['shipmentStatusIndicatorCode', 'shipmentAppointmentStatusCode']

// ---- Helpers -------------------------------------------------------------------------

def walk
walk = { node, Closure visitor ->
    if (node instanceof Map) {
        visitor(node)
        node.values().each { walk(it, visitor) }
    } else if (node instanceof List) {
        node.each { walk(it, visitor) }
    }
}

def asList = { v -> v instanceof List ? v : (v == null ? [] : [v]) }

/** Every occurrence of a segment under root, as [segment: Map, loop: Map holding it]. */
def segments = { root, String name ->
    List found = []
    walk(root) { Map m -> asList(m[name]).each { if (it instanceof Map) found << [segment: it, loop: m] } }
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

/** Only characters that can appear in a container/BOL number; also makes values SuiteQL-safe. */
def clean = { v -> v ? v.toString().toUpperCase().replaceAll(/[^A-Z0-9\-]/, '') ?: null : null }

/** MS2-01 owner prefix + MS2-02 number + MS2-03 check digit, e.g. TSTU + 123456 + 0. */
def containerFromMs2 = { Map ms2 ->
    if (!ms2?.equipmentNumber) return null
    String owner = ms2.standardCarrierAlphaCode ?: ms2.equipmentInitial ?: ''
    String num = ms2.equipmentNumber.startsWith(owner) ? ms2.equipmentNumber : owner + ms2.equipmentNumber
    clean(num + (ms2.equipmentNumberCheckDigit ?: ''))
}

def eqRefs = { scope -> segments(scope, SEG_L11)*.segment
        .findAll { it.referenceIdentificationQualifier == 'EQ' }
        .collect { clean(it.referenceIdentification) }.findAll { it }.unique() }

def logger = ExecutionUtil.getBaseLogger()

def recordResult = { Map r ->
    List all = new JsonSlurper().parseText(ExecutionUtil.getDynamicProcessProperty('TNC_214_RESULTS') ?: '[]')
    all << r
    ExecutionUtil.setDynamicProcessProperty('TNC_214_RESULTS', JsonOutput.toJson(all), false)
}

// ---- Main ----------------------------------------------------------------------------

for (int i = 0; i < dataContext.getDataCount(); i++) {
    InputStream is = dataContext.getStream(i)
    Properties props = dataContext.getProperties(i)
    def doc = new JsonSlurper().parseText(new String(is.getBytes(), 'UTF-8'))
    String txnId = props.getProperty('document.dynamic.userdefined.orderfulTransactionId') ?: doc?.id?.toString()

    List sets = []
    walk(doc) { Map m -> if (m.containsKey(SEG_B10)) sets << m }
    if (!sets) {
        logger.warning("Orderful transaction ${txnId}: no 214 transaction set (B10) found")
        recordResult([orderfulTransactionId: txnId, result: 'INVALID', message: 'No 214 transaction set (B10) found'])
        continue
    }

    sets.each { Map ts ->
        Map b10 = asList(ts[SEG_B10]).find() ?: [:]
        String bol = clean(b10.shipmentIdentificationNumber)

        // Header-level L11*EQ only (detail-level ones are read per LX loop below).
        List headerEq = asList(ts[SEG_L11]).findAll { it instanceof Map && it.referenceIdentificationQualifier == 'EQ' }
                                           .collect { clean(it.referenceIdentification) }.findAll { it }.unique()
        List lxLoops = asList(ts[LOOP_LX]) ?: [ts]

        List events = []
        lxLoops.each { lx ->
            List lxEq = lx.is(ts) ? [] : eqRefs(lx)
            segments(lx, SEG_AT7).each { s ->
                Map at7 = s.segment
                Map ms1 = segments(s.loop, SEG_MS1).find()?.segment
                Map ms2 = segments(s.loop, SEG_MS2).find()?.segment
                String code = AT7_STATUS_ELEMENTS.collect { at7[it] }.find { it }
                if (!code) return
                events << [code     : code,
                           reason   : at7.shipmentStatusOrAppointmentReasonCode,
                           at       : toIso(at7.date, at7.time, at7.timeCode),
                           location : ms1 ? [ms1.cityName, ms1.stateOrProvinceCode, ms1.countryCode].findAll { it }.join(', ') : null,
                           container: containerFromMs2(ms2) ?: (lxEq.size() == 1 ? lxEq[0] : null)]
            }
        }

        if (!bol || !events) {
            String why = !bol ? 'missing B10-02 shipment identification number' : 'no AT7 shipment status'
            logger.warning("Orderful transaction ${txnId}: ${why}")
            recordResult([orderfulTransactionId: txnId, bol: bol, result: 'INVALID', message: "214 ${why}"])
            return
        }

        // One known container in the whole set -> events without their own MS2 belong to it.
        List known = (events*.container.findAll { it } + headerEq).unique()
        if (known.size() == 1) events.each { if (!it.container) it.container = known[0] }

        events.groupBy { it.container }.each { container, evs ->
            def latest = evs.findAll { it.at }.max { it.at } ?: evs[-1]
            Map out = [
                orderfulTransactionId: txnId,
                bol                  : bol,
                containerNumber      : container,
                scac                 : b10.standardCarrierAlphaCode,
                carrierReference     : b10.referenceIdentification,
                statusCode           : latest.code,
                statusReason         : latest.reason,
                statusDateTime       : latest.at,
                statusDate           : latest.at?.take(10),
                location             : latest.location,
                eventCount           : evs.size(),
            ]
            Properties p = new Properties()
            p.putAll(props)   // each output document needs its own properties
            p.setProperty('document.dynamic.userdefined.bol', bol)
            p.setProperty('document.dynamic.userdefined.containerNumber', container ?: '')
            p.setProperty('document.dynamic.userdefined.orderfulTransactionId', txnId ?: '')
            logger.info("214 ${latest.code} @ ${latest.at} BOL=${bol} container=${container}")
            dataContext.storeStream(new ByteArrayInputStream(JsonOutput.toJson(out).getBytes('UTF-8')), p)
        }
    }
}
