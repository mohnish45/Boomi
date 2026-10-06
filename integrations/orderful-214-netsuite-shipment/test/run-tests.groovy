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

// 1. Normalize: latest AT7 wins, container = MS2-01 + MS2-02, SuiteQL built.
def n = run('scripts/Normalize214.groovy', new File('samples/orderful-214.json').text)
def c = json.parseText(ExecutionUtil.dpps.TNC_214_CANONICAL)
check(c.statusCode == 'AF', 'latest AT7 status selected')
check(c.statusDateTime == '2026-10-03T14:30:00', 'AT7 date/time converted to ISO')
check(c.containerNumber == 'MSCU1234567', 'container number assembled from MS2')
check(c.billOfLading == 'MAEU123456789', 'BOL taken from L11*BM')
check(c.orderfulTransactionId == '98765432', 'Orderful transaction id captured')
check(c.location == null, 'location comes from the latest event loop, not an earlier one')
check(json.parseText(n.body).q.contains("UPPER(externaldocumentnumber) = 'MSCU1234567'"), 'SuiteQL lookup built')

// 2. Patch: AF moves toBeShipped -> inTransit and sets ship date.
def p = patchFor(new File('samples/suiteql-response.json').text)
def body = json.parseText(p.body)
check(p.props.getProperty('document.dynamic.userdefined.nsAction') == 'PATCH', 'action PATCH')
check(p.props.getProperty('document.dynamic.userdefined.nsRecordId') == '4521', 'record id set for URL')
check(body.shipmentStatus.id == 'inTransit' && body.actualShippingDate == '2026-10-03', 'status + ship date patched')

// 3. Older event than what NetSuite already has -> STALE.
def stale = patchFor('{"items":[{"id":"4521","shipmentstatus":"inTransit","laststatusdt":"2026-10-04T08:00:00"}]}')
check(stale.props.getProperty('document.dynamic.userdefined.nsAction') == 'STALE', 'out-of-order event skipped')

// 4. Already received -> never move status back.
def recvd = json.parseText(patchFor('{"items":[{"id":"4521","shipmentstatus":"received"}]}').body)
check(recvd.shipmentStatus == null, 'received shipment status not downgraded')

// 5. No / multiple matches.
check(patchFor('{"items":[]}').props.getProperty('document.dynamic.userdefined.nsAction') == 'NOT_FOUND', 'no match -> NOT_FOUND')
check(patchFor('{"items":[{"id":"1"},{"id":"2"}]}').props.getProperty('document.dynamic.userdefined.nsAction') == 'AMBIGUOUS', 'two matches -> AMBIGUOUS')

// 6. Missing container and BOL fails the document.
try { run('scripts/Normalize214.groovy', '{"shipmentStatusCode":"AF","date":"20261003"}'); check(false, 'should throw') }
catch (IllegalStateException e) { check(true, 'missing container/BOL rejected') }
println 'all tests passed'
