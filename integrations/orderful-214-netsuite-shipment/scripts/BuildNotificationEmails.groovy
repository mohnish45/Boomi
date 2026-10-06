/*
 * Boomi Data Process (Custom Scripting, Groovy) — BuildNotificationEmails
 *
 * Runs once at the end (last Branch path), after all updates. Reads DPP TNC_214_RESULTS.
 * Output: up to two HTML email bodies, each with DDPs
 *   emailAudience = USERS | DEVELOPER   (Route on this to the two Mail shapes)
 *   emailSubject  = subject line        (Set Properties: Mail connector Subject <- this DDP)
 *   USERS     — completion summary, sent whenever this run processed at least one 214.
 *   DEVELOPER — sent when a 214 couldn't be processed (INVALID). Process-level errors
 *               (connection failures, script exceptions) reach the developer through the
 *               Try/Catch path instead.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// Also copy the developer on failed NetSuite updates (e.g. auth or field-id problems).
final boolean DEVELOPER_ON_UPDATE_FAILURE = false

List results = new JsonSlurper().parseText(ExecutionUtil.getDynamicProcessProperty('TNC_214_RESULTS') ?: '[]')
// Drain the input documents (the Branch passes the 214s here too); they are not needed.
for (int i = 0; i < dataContext.getDataCount(); i++) dataContext.getStream(i).close()
if (!results) return

String execId = ExecutionUtil.getRuntimeExecutionProperty('EXECUTION_ID') ?: ''
def esc = { v -> v == null ? '' : v.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;') }
def count = { String r -> results.count { it.result == r } }

def table = { List rows ->
    String head = ['Result', 'B10-02 / BOL', 'Container', 'Inbound shipment', 'Status', 'Event time', 'Message']
            .collect { "<th align=\"left\">${it}</th>" }.join('')
    String body = rows.collect { r ->
        '<tr>' + [r.result, r.bol, r.containerNumber, r.shipmentNumber ?: r.nsRecordId, r.statusCode, r.statusDateTime, r.message]
                .collect { "<td>${esc(it)}</td>" }.join('') + '</tr>'
    }.join('\n')
    "<table border=\"1\" cellpadding=\"4\" cellspacing=\"0\"><tr>${head}</tr>\n${body}</table>"
}

def emit = { String audience, String subject, String html ->
    Properties p = new Properties()
    p.setProperty('document.dynamic.userdefined.emailAudience', audience)
    p.setProperty('document.dynamic.userdefined.emailSubject', subject)
    dataContext.storeStream(new ByteArrayInputStream(html.getBytes('UTF-8')), p)
}

String summary = "${count('UPDATED')} updated, ${count('NOT_FOUND')} not in NetSuite, " +
                 "${count('UPDATE_FAILED')} failed, ${count('STALE')} skipped (older event), ${count('INVALID')} invalid"
emit('USERS', "Orderful 214 to NetSuite inbound shipments completed: ${summary}",
     "<p>Orderful 214 shipment status processing completed.</p><p>${esc(summary)}</p>" +
     table(results) + "<p>Boomi execution ${esc(execId)}</p>")

List devRows = results.findAll { it.result == 'INVALID' || (DEVELOPER_ON_UPDATE_FAILURE && it.result == 'UPDATE_FAILED') }
if (devRows) {
    emit('DEVELOPER', "ERROR: Orderful 214 to NetSuite - ${devRows.size()} transaction(s) need attention",
         "<p>These Orderful 214 transactions could not be processed.</p>" + table(devRows) +
         "<p>Boomi execution ${esc(execId)}. Check Process Reporting for the full log.</p>")
}
