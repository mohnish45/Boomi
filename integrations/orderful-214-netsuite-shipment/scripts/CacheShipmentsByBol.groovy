/*
 * Boomi Data Process (Custom Scripting, Groovy) — CacheShipmentsByBol
 *
 * Input : SuiteQL responses from the lookup built by BuildInboundShipmentLookup.
 * Output: one document per B10-02 number found in NetSuite, for an "Add to Cache" shape:
 *   { "bol": "OCEANBL0000001", "shipmentCount": 2,
 *     "shipmentsJson": "[{\"id\":\"4521\",\"shipmentnumber\":\"INBSHIP123\",...}]" }
 * Document Cache "NS Inbound Shipments by BOL": JSON profile with these three fields,
 * index "BOL" keyed on `bol`. The shipment list is cached as one JSON string so a single
 * cache lookup in Set Properties returns every inbound shipment for the number.
 */
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def logger = ExecutionUtil.getBaseLogger()
Map byBol = [:].withDefault { [] }

for (int i = 0; i < dataContext.getDataCount(); i++) {
    def resp = new JsonSlurper().parseText(new String(dataContext.getStream(i).getBytes(), 'UTF-8'))
    if (resp?.hasMore) {
        // More than one page would silently drop shipments; fail so the developer is emailed.
        throw new IllegalStateException("SuiteQL inbound shipment lookup returned more than one page " +
                "(totalResults=${resp.totalResults}); lower CHUNK in BuildInboundShipmentLookup")
    }
    (resp?.items ?: []).each { Map item ->
        item.remove('links')
        if (item.bol) byBol[item.bol.toString()] << item
    }
}

byBol.each { String bol, List shipments ->
    logger.info("BOL ${bol}: ${shipments.size()} inbound shipment(s) ${shipments*.shipmentnumber}")
    Map out = [bol: bol, shipmentCount: shipments.size(), shipmentsJson: JsonOutput.toJson(shipments)]
    dataContext.storeStream(new ByteArrayInputStream(JsonOutput.toJson(out).getBytes('UTF-8')), new Properties())
}
