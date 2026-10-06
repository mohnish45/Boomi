/*
 * Boomi Data Process (Custom Scripting, Groovy) — BuildNetSuitePatch
 *
 * Input : the NetSuite SuiteQL response for the lookup built by Normalize214.groovy
 *         ({"items":[{"id":"123","shipmentstatus":"inTransit","laststatusdt":"..."}], ...}).
 * Reads : DPP TNC_214_CANONICAL (set by Normalize214.groovy).
 * Output: the PATCH body for /services/rest/record/v1/inboundShipment/{id}.
 *
 * Sets DDPs:
 *   nsRecordId — replacement variable for the HTTP Client operation's resource path
 *   nsAction   — PATCH | NOT_FOUND | AMBIGUOUS | STALE; put a Route shape on
 *                this right after the script so only PATCH goes to NetSuite.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// ---- Configuration -------------------------------------------------------------------

// Inbound Shipment status ids (REST): toBeShipped, inTransit, partiallyReceived, received, closed.
// Only ever move the status forward to inTransit; "received" happens when the warehouse
// receives the shipment in NetSuite (that creates item receipts), never from a 214.
final List IN_TRANSIT_CODES     = ['AF', 'X6', 'CD', 'P1', 'AM', 'AV', 'I1', 'OA']
final List DELIVERED_CODES      = ['X1', 'D1']
final List ESTIMATED_DELIVERY   = ['AG', 'AB']
final List LOCKED_STATUSES      = ['partiallyReceived', 'received', 'closed']

// Custom fields on the shipment record (placeholders — replace with TNC's script ids).
final String F_LAST_STATUS      = 'custrecord_tnc_ib_edi_status'
final String F_LAST_STATUS_DT   = 'custrecord_tnc_ib_edi_status_dt' // Free-Form Text holding ISO-8601, so it compares as text
final String F_LAST_LOCATION    = 'custrecord_tnc_ib_edi_location'
final String F_ORDERFUL_TXN     = 'custrecord_tnc_ib_orderful_txn'

// ---- Main ----------------------------------------------------------------------------

def logger = ExecutionUtil.getBaseLogger()
Map c = new JsonSlurper().parseText(ExecutionUtil.getDynamicProcessProperty('TNC_214_CANONICAL'))

for (int i = 0; i < dataContext.getDataCount(); i++) {
    InputStream is = dataContext.getStream(i)
    Properties props = dataContext.getProperties(i)
    def resp = new JsonSlurper().parseText(new String(is.getBytes(), 'UTF-8'))
    List items = resp?.items ?: []

    String action
    Map patch = [:]

    if (items.isEmpty()) {
        action = 'NOT_FOUND'
    } else if (items.size() > 1) {
        action = 'AMBIGUOUS'
    } else {
        Map rec = items[0]
        props.setProperty('document.dynamic.userdefined.nsRecordId', rec.id.toString())

        // 214s can arrive out of order; never let an older event overwrite a newer one.
        // ISO-8601 strings with the same offset compare correctly as text.
        if (rec.laststatusdt && c.statusDateTime && c.statusDateTime < rec.laststatusdt.toString()) {
            action = 'STALE'
        } else {
            String code = c.statusCode
            String currentStatus = rec.shipmentstatus?.toString()

            if (code in IN_TRANSIT_CODES && !(currentStatus in LOCKED_STATUSES) && currentStatus != 'inTransit') {
                patch.shipmentStatus = [id: 'inTransit']
            }
            if (code == 'AF' && c.statusDate) patch.actualShippingDate = c.statusDate
            if (code in ESTIMATED_DELIVERY && c.statusDate) patch.expectedDeliveryDate = c.statusDate
            if (code in DELIVERED_CODES && c.statusDate) patch.actualDeliveryDate = c.statusDate
            if (c.billOfLading) patch.billOfLading = c.billOfLading

            patch[F_LAST_STATUS]    = c.statusCode + (c.statusReason ? "/${c.statusReason}" : '')
            patch[F_LAST_STATUS_DT] = c.statusDateTime
            if (c.location) patch[F_LAST_LOCATION] = c.location
            if (c.orderfulTransactionId) patch[F_ORDERFUL_TXN] = c.orderfulTransactionId

            action = 'PATCH'
        }
    }

    props.setProperty('document.dynamic.userdefined.nsAction', action)
    logger.info("214 ${c.statusCode} container=${c.containerNumber}: ${action}" +
                (action == 'PATCH' ? " id=${items[0].id} body=${JsonOutput.toJson(patch)}" : ''))

    byte[] out = JsonOutput.toJson(action == 'PATCH' ? patch : [action: action, canonical: c]).getBytes('UTF-8')
    dataContext.storeStream(new ByteArrayInputStream(out), props)
}
