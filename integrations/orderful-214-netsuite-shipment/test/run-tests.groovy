// Runs both Data Process scripts against the sample payloads with a fake dataContext.
// Usage (from this folder): groovy -cp test/stubs test/run-tests.groovy
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonSlurper

class FakeDataContext {
    List<InputStream> ins = []; List<Properties> inProps = []
    List<String> outs = []; List<Properties> outProps = []
    int getDataCount() { ins.size() }
    InputStream getStream(int i) { ins[i] }
    Properties getProperties(int i) { inProps[i] }
    void storeStream(InputStream is, Properties p) { outs << new String(is.bytes, 'UTF-8'); outProps << p }
}

def run = { String script, String input, Properties props = new Properties() ->
    def ctx = new FakeDataContext(ins: [new ByteArrayInputStream(input.getBytes('UTF-8'))], inProps: [props])
    new GroovyShell(new Binding(dataContext: ctx)).evaluate(new File(script))
    [body: ctx.outs[0], props: ctx.outProps[0]]
}
def check = { boolean ok, String msg -> if (!ok) throw new AssertionError(msg); println "ok - $msg" }
def json = new JsonSlurper()
def patchFor = { String suiteql -> run('scripts/BuildNetSuitePatch.groovy', suiteql) }

// 1. Normalize the real Orderful 214 (drayage, port -> warehouse; identifiers anonymized).
def sample = new File('samples/orderful-214.json').text
def props1 = new Properties(); props1.setProperty('document.dynamic.userdefined.orderfulTransactionId', '1000000001')
def n = run('scripts/Normalize214.groovy', sample, props1)
def c = json.parseText(ExecutionUtil.dpps.TNC_214_CANONICAL)
check(c.statusCode == 'C2' && c.statusReason == 'NS', 'AT7-01/02 read from shipmentStatusIndicatorCode')
check(c.statusDateTime == '2026-08-24T06:00:00', 'AT7 date/time converted to ISO')
check(c.containerNumber == 'TSTU1234560', 'container from L11*EQ')
check(c.billOfLading == 'OCEANBL0000001', 'ocean BOL from B10-02')
check(c.scac == 'CARR' && c.carrierReference == 'REF-0001', 'B10 SCAC and carrier reference')
check(c.location == 'Port City, GA', 'location from the AT7 loop MS1, not the N1 parties')
check(c.orderfulTransactionId == '1000000001', 'Orderful transaction id from DDP')
check(json.parseText(n.body).q.contains("UPPER(externaldocumentnumber) = 'TSTU1234560'"), 'SuiteQL lookup built')

// 1b. Without L11*EQ the container is assembled from MS2 (owner + number + check digit).
def noEq = json.parseText(sample); noEq.transactionSets[0].remove('businessInstructionsAndReferenceNumber')
run('scripts/Normalize214.groovy', groovy.json.JsonOutput.toJson(noEq))
check(json.parseText(ExecutionUtil.dpps.TNC_214_CANONICAL).containerNumber == 'TSTU1234560', 'container assembled from MS2')

// 1c. Several AT7 loops: the latest event wins, with its own location.
def multi = json.parseText(sample)
multi.transactionSets[0].LX_loop[0].AT7_loop << [
    shipmentStatusDetails: [[shipmentStatusIndicatorCode: 'AF', shipmentStatusOrAppointmentReasonCode: 'NS', date: '20260825', time: '1015', timeCode: 'LT']],
    equipmentShipmentOrRealPropertyLocation: [[cityName: 'Warehouse City', stateOrProvinceCode: 'GA']]]
run('scripts/Normalize214.groovy', groovy.json.JsonOutput.toJson(multi))
def cm = json.parseText(ExecutionUtil.dpps.TNC_214_CANONICAL)
check(cm.statusCode == 'AF' && cm.location == 'Warehouse City, GA', 'latest AT7 loop selected with its location')

// 2. Patch: AF sets the actual ship date; status already inTransit is left alone.
def p = patchFor(new File('samples/suiteql-response.json').text)
def body = json.parseText(p.body)
check(p.props.getProperty('document.dynamic.userdefined.nsAction') == 'PATCH', 'action PATCH')
check(p.props.getProperty('document.dynamic.userdefined.nsRecordId') == '4521', 'record id set for URL')
check(body.shipmentStatus == null && body.actualShippingDate == '2026-08-25', 'ship date patched, status unchanged')
check(json.parseText(patchFor('{"items":[{"id":"4521","shipmentstatus":"toBeShipped"}]}').body).shipmentStatus.id == 'inTransit', 'toBeShipped -> inTransit')

// 3. Older event than what NetSuite already has -> STALE.
def stale = patchFor('{"items":[{"id":"4521","shipmentstatus":"inTransit","laststatusdt":"2026-08-26T08:00:00"}]}')
check(stale.props.getProperty('document.dynamic.userdefined.nsAction') == 'STALE', 'out-of-order event skipped')

// 4. Already received -> never move status back.
def recvd = json.parseText(patchFor('{"items":[{"id":"4521","shipmentstatus":"received"}]}').body)
check(recvd.shipmentStatus == null, 'received shipment status not downgraded')

// 5. No / multiple matches.
check(patchFor('{"items":[]}').props.getProperty('document.dynamic.userdefined.nsAction') == 'NOT_FOUND', 'no match -> NOT_FOUND')
check(patchFor('{"items":[{"id":"1"},{"id":"2"}]}').props.getProperty('document.dynamic.userdefined.nsAction') == 'AMBIGUOUS', 'two matches -> AMBIGUOUS')

// 6. Missing container and BOL fails the document.
try { run('scripts/Normalize214.groovy', '{"shipmentStatusDetails":[{"shipmentStatusIndicatorCode":"AF","date":"20261003"}]}'); check(false, 'should throw') }
catch (IllegalStateException e) { check(true, 'missing container/BOL rejected') }
println 'all tests passed'
