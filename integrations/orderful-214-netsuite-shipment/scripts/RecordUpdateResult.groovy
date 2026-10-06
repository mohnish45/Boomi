/*
 * Boomi Data Process (Custom Scripting, Groovy) — RecordUpdateResult
 *
 * Input : NetSuite responses from the PATCH HTTP Client shape. Turn on "Return Application
 *         Error Responses" in that operation so failed updates arrive here instead of
 *         stopping the process, and put a Set Properties shape before this one:
 *           DDP httpStatus  <- Meta Information > Base > Application Status Code
 *           DDP httpMessage <- Meta Information > Base > Application Status Message
 * Output: none. Each update is logged and recorded as UPDATED / UPDATE_FAILED in
 *         DPP TNC_214_RESULTS for the completion email.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def logger = ExecutionUtil.getBaseLogger()
def ddp = { Properties p, String name -> p.getProperty("document.dynamic.userdefined.${name}") ?: null }

List all = new JsonSlurper().parseText(ExecutionUtil.getDynamicProcessProperty('TNC_214_RESULTS') ?: '[]')

for (int i = 0; i < dataContext.getDataCount(); i++) {
    String body = new String(dataContext.getStream(i).getBytes(), 'UTF-8')
    Properties props = dataContext.getProperties(i)
    String status = ddp(props, 'httpStatus') ?: ''
    Map r = [orderfulTransactionId: ddp(props, 'orderfulTransactionId'), bol: ddp(props, 'bol'),
             containerNumber: ddp(props, 'containerNumber'), statusCode: ddp(props, 'statusCode'),
             statusDateTime: ddp(props, 'statusDateTime'), nsRecordId: ddp(props, 'nsRecordId'),
             shipmentNumber: ddp(props, 'nsShipmentNumber')]

    if (status ==~ /2\d\d/) {
        logger.info("Updated inbound shipment ${r.shipmentNumber} (id ${r.nsRecordId}) BOL=${r.bol} with ${r.statusCode}")
        all << (r + [result: 'UPDATED'])
    } else {
        // NetSuite REST errors: {"title": ..., "o:errorDetails": [{"detail": ..., "o:errorCode": ...}]}
        String detail = null
        try {
            def err = new JsonSlurper().parseText(body)
            detail = err?.'o:errorDetails'?.collect { "${it.'o:errorCode' ?: ''} ${it.detail ?: ''}".trim() }?.join('; ') ?: err?.title
        } catch (ignored) { }
        String msg = "HTTP ${status ?: '?'} ${detail ?: ddp(props, 'httpMessage') ?: body.take(500)}".trim()
        logger.severe("Update failed for inbound shipment ${r.shipmentNumber} (id ${r.nsRecordId}) BOL=${r.bol} container=${r.containerNumber}: ${msg}")
        all << (r + [result: 'UPDATE_FAILED', message: msg])
    }
}

ExecutionUtil.setDynamicProcessProperty('TNC_214_RESULTS', JsonOutput.toJson(all), false)
