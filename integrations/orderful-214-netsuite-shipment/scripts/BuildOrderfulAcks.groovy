/*
 * Boomi Data Process (Custom Scripting, Groovy) — BuildOrderfulAcks
 *
 * Runs after all updates (Branch path before the emails). Reads DPP TNC_214_RESULTS.
 * Output: one document per Orderful transaction that is finished, with DDP
 *         orderfulTransactionId, for the HTTP Client call that acknowledges/removes it in
 *         Orderful. A transaction with any UPDATE_FAILED container is left unacknowledged
 *         so the next poll retries it.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonSlurper

// Acknowledge a transaction whose B10-02 isn't in NetSuite (it is logged and emailed).
// false = keep it in Orderful and retry every poll until the inbound shipment exists.
final boolean ACK_NOT_FOUND = true
final boolean ACK_INVALID   = true

List results = new JsonSlurper().parseText(ExecutionUtil.getDynamicProcessProperty('TNC_214_RESULTS') ?: '[]')
for (int i = 0; i < dataContext.getDataCount(); i++) dataContext.getStream(i).close()

results.findAll { it.orderfulTransactionId }.groupBy { it.orderfulTransactionId }.each { txn, rs ->
    boolean done = rs.every { r ->
        r.result in ['UPDATED', 'STALE'] ||
        (r.result == 'NOT_FOUND' && ACK_NOT_FOUND) ||
        (r.result == 'INVALID' && ACK_INVALID)
    }
    if (done) {
        Properties p = new Properties()
        p.setProperty('document.dynamic.userdefined.orderfulTransactionId', txn.toString())
        dataContext.storeStream(new ByteArrayInputStream('{}'.getBytes('UTF-8')), p)
    }
}
