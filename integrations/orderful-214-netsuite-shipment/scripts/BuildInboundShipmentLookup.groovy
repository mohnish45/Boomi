/*
 * Boomi Data Process (Custom Scripting, Groovy) — BuildInboundShipmentLookup
 *
 * Input : all container status documents from Split214ByContainer (Branch 1).
 * Output: SuiteQL request bodies ({"q": "..."}) that fetch every inbound shipment for the
 *         batch's B10-02 numbers in one call (chunked so the IN list stays small).
 *         POST each to /services/rest/query/v1/suiteql?limit=1000 with header
 *         "Prefer: transient".
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// NetSuite record + fields. The B10-02 number (ocean bill of lading) is matched against
// NS_BOL_FIELD. Change these if TNC keeps them in other/custom fields.
final String NS_TABLE             = 'inboundshipment'
final String NS_BOL_FIELD         = 'billoflading'
final String NS_CONTAINER_FIELD   = 'externaldocumentnumber'
final String NS_LAST_STATUS_FIELD = 'custrecord_tnc_ib_edi_status_dt' // Free-Form Text, ISO-8601
final int    CHUNK                = 200

Set bols = new LinkedHashSet()
for (int i = 0; i < dataContext.getDataCount(); i++) {
    def doc = new JsonSlurper().parseText(new String(dataContext.getStream(i).getBytes(), 'UTF-8'))
    // Values were already restricted to [A-Z0-9-] by Split214ByContainer; re-check here
    // because they are placed inside the SQL text.
    if (doc.bol && doc.bol ==~ /[A-Z0-9\-]+/) bols << doc.bol
}

bols.toList().collate(CHUNK).each { List chunk ->
    String q = "SELECT id, shipmentnumber, UPPER(${NS_BOL_FIELD}) AS bol, " +
               "UPPER(${NS_CONTAINER_FIELD}) AS containernumber, shipmentstatus, " +
               "${NS_LAST_STATUS_FIELD} AS laststatusdt " +
               "FROM ${NS_TABLE} WHERE UPPER(${NS_BOL_FIELD}) IN (${chunk.collect { "'${it}'" }.join(', ')})"
    dataContext.storeStream(new ByteArrayInputStream(JsonOutput.toJson([q: q]).getBytes('UTF-8')), new Properties())
}
