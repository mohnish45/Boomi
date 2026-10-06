/*
 * Boomi Data Process (Custom Scripting, Groovy) — BuildNetSuitePatches
 *
 * Input : container status documents (Branch 2), each with DDP nsShipmentsJson set just
 *         before this shape by a Set Properties shape reading Document Cache
 *         "NS Inbound Shipments by BOL" (index BOL, key = DDP bol, field shipmentsJson).
 * Output: one PATCH body per inbound shipment to update, for
 *         PATCH /services/rest/record/v1/inboundShipment/{nsRecordId}
 *   DDPs: nsRecordId, nsShipmentNumber, bol, containerNumber, orderfulTransactionId, statusCode
 *
 * Not found in the cache -> logs "Container: <B10-02> not in netsuite" and records NOT_FOUND.
 * Older than the event already applied to the shipment -> records STALE, no update.
 * Both go to DPP TNC_214_RESULTS for the completion email; only PATCH documents are output.
 *
 * Which shipments get updated: the shipment(s) under the B10-02 number whose container
 * number matches the 214's container; if none match (or the 214 has no container), every
 * inbound shipment under the B10-02 number.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// ---- Status rules --------------------------------------------------------------------
// Inbound Shipment status ids (REST): toBeShipped, inTransit, partiallyReceived, received, closed.
// A 214 only ever moves the status forward to inTransit. "Received" is set by receiving the
// shipment in NetSuite (that creates the item receipts), never from a 214.
final List IN_TRANSIT_CODES   = ['AF', 'X6', 'CD', 'P1', 'AM', 'AV', 'I1', 'OA']
final List LOCKED_STATUSES    = ['partiallyReceived', 'received', 'closed']
// AT7 code -> date field set to the event date.
final Map  DATE_FIELD_BY_CODE = [
    AF: 'actualShippingDate',               // departed pickup location
    AG: 'expectedDeliveryDate',             // estimated delivery
    AB: 'expectedDeliveryDate',             // delivery appointment
    X1: 'actualDeliveryDate',               // arrived at delivery location
    D1: 'actualDeliveryDate',               // completed unloading
    C2: 'custrecord_tnc_ib_empty_return_dt' // delivered empty (container returned) - confirm with carrier
]

// ---- NetSuite fields -----------------------------------------------------------------
final String F_CONTAINER        = 'externalDocumentNumber'          // only filled when empty
final String F_LAST_STATUS      = 'custrecord_tnc_ib_edi_status'    // placeholders: replace with
final String F_LAST_STATUS_DT   = 'custrecord_tnc_ib_edi_status_dt' //   TNC's custom field ids
final String F_LAST_LOCATION    = 'custrecord_tnc_ib_edi_location'
final String F_CARRIER          = 'custrecord_tnc_ib_edi_carrier'
final String F_CARRIER_REF      = 'custrecord_tnc_ib_edi_carrier_ref'
final String F_ORDERFUL_TXN     = 'custrecord_tnc_ib_orderful_txn'

def logger = ExecutionUtil.getBaseLogger()

def recordResult = { Map r ->
    List all = new JsonSlurper().parseText(ExecutionUtil.getDynamicProcessProperty('TNC_214_RESULTS') ?: '[]')
    all << r
    ExecutionUtil.setDynamicProcessProperty('TNC_214_RESULTS', JsonOutput.toJson(all), false)
}

for (int i = 0; i < dataContext.getDataCount(); i++) {
    InputStream is = dataContext.getStream(i)
    Properties props = dataContext.getProperties(i)
    Map c = new JsonSlurper().parseText(new String(is.getBytes(), 'UTF-8'))
    String cached = props.getProperty('document.dynamic.userdefined.nsShipmentsJson')
    List shipments = cached ? new JsonSlurper().parseText(cached) : []
    Map base = [orderfulTransactionId: c.orderfulTransactionId, bol: c.bol, containerNumber: c.containerNumber,
                statusCode: c.statusCode, statusDateTime: c.statusDateTime]

    if (!shipments) {
        logger.warning("Container: ${c.bol} not in netsuite")
        recordResult(base + [result: 'NOT_FOUND', message: "Container: ${c.bol} not in netsuite"])
        continue
    }

    List targets = c.containerNumber ? shipments.findAll { it.containernumber == c.containerNumber } : []
    if (!targets) targets = shipments

    targets.each { Map s ->
        Map r = base + [nsRecordId: s.id?.toString(), shipmentNumber: s.shipmentnumber]

        // 214s can arrive out of order; never let an older event overwrite a newer one.
        if (s.laststatusdt && c.statusDateTime && c.statusDateTime < s.laststatusdt.toString()) {
            recordResult(r + [result: 'STALE', message: "Event ${c.statusDateTime} older than ${s.laststatusdt}"])
            return
        }

        Map patch = [:]
        if (c.statusCode in IN_TRANSIT_CODES && s.shipmentstatus == 'toBeShipped') patch.shipmentStatus = [id: 'inTransit']
        String dateField = DATE_FIELD_BY_CODE[c.statusCode]
        if (dateField && c.statusDate) patch[dateField] = c.statusDate
        if (c.containerNumber && !s.containernumber && shipments.size() == 1) patch[F_CONTAINER] = c.containerNumber
        patch[F_LAST_STATUS]    = c.statusCode + (c.statusReason ? "/${c.statusReason}" : '')
        patch[F_LAST_STATUS_DT] = c.statusDateTime
        if (c.location)              patch[F_LAST_LOCATION] = c.location
        if (c.scac)                  patch[F_CARRIER]       = c.scac
        if (c.carrierReference)      patch[F_CARRIER_REF]   = c.carrierReference
        if (c.orderfulTransactionId) patch[F_ORDERFUL_TXN]  = c.orderfulTransactionId

        Properties p = new Properties()
        p.putAll(props)
        p.remove('document.dynamic.userdefined.nsShipmentsJson')
        p.setProperty('document.dynamic.userdefined.nsRecordId', r.nsRecordId)
        p.setProperty('document.dynamic.userdefined.nsShipmentNumber', r.shipmentNumber?.toString() ?: '')
        p.setProperty('document.dynamic.userdefined.statusCode', c.statusCode)
        p.setProperty('document.dynamic.userdefined.statusDateTime', c.statusDateTime ?: '')
        logger.info("PATCH inbound shipment ${s.shipmentnumber} (id ${s.id}) BOL=${c.bol}: ${JsonOutput.toJson(patch)}")
        dataContext.storeStream(new ByteArrayInputStream(JsonOutput.toJson(patch).getBytes('UTF-8')), p)
    }
}
