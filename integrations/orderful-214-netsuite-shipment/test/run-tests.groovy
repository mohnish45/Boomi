// Runs the Data Process scripts in process order against the sample payloads, with a fake
// dataContext and ExecutionUtil. Shapes that aren't scripts (HTTP calls, Document Cache,
// Set Properties) are simulated in between.
// Usage (from this folder): groovy -cp test/stubs test/run-tests.groovy
import com.boomi.execution.ExecutionUtil
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class FakeDataContext {
    List<InputStream> ins = []; List<Properties> inProps = []
    List<Map> outs = []
    int getDataCount() { ins.size() }
    InputStream getStream(int i) { ins[i] }
    Properties getProperties(int i) { inProps[i] }
    void storeStream(InputStream is, Properties p) { outs << [body: new String(is.bytes, 'UTF-8'), props: p] }
}

def DDP = 'document.dynamic.userdefined.'
def json = new JsonSlurper()
/** Run a script over documents given as [body: String, props: Map of DDPs]; returns outputs. */
def run = { String script, List docs ->
    def ctx = new FakeDataContext()
    docs.each { d ->
        ctx.ins << new ByteArrayInputStream(d.body.getBytes('UTF-8'))
        Properties p = new Properties(); (d.props ?: [:]).each { k, v -> p.setProperty(DDP + k, v) }
        ctx.inProps << p
    }
    new GroovyShell(new Binding(dataContext: ctx)).evaluate(new File("scripts/${script}.groovy"))
    ctx.outs.collect { [body: it.body, json: it.body.startsWith('{') ? json.parseText(it.body) : null,
                        ddp: { String k -> it.props.getProperty(DDP + k) }] }
}
def check = { boolean ok, String msg -> if (!ok) throw new AssertionError(msg); println "ok - $msg" }
def results = { json.parseText(ExecutionUtil.dpps.TNC_214_RESULTS ?: '[]') }
def reset = { ExecutionUtil.dpps.clear() }

// ---- 1. Split ---------------------------------------------------------------------------
reset()
def single = run('Split214ByContainer', [[body: new File('samples/orderful-214.json').text, props: [orderfulTransactionId: '1000000001']]])
check(single.size() == 1, 'single-container 214 -> 1 document')
def s1 = single[0].json
check(s1.bol == 'OCEANBL0000001' && single[0].ddp('bol') == 'OCEANBL0000001', 'B10-02 is the lookup key (DDP bol)')
check(s1.containerNumber == 'TSTU1234560', 'container from MS2 / L11*EQ')
check(s1.statusCode == 'C2' && s1.statusReason == 'NS' && s1.statusDateTime == '2026-08-24T06:00:00', 'AT7 status and event time')
check(s1.location == 'Port City, GA' && s1.scac == 'CARR' && s1.carrierReference == 'REF-0001', 'location, SCAC, carrier reference')

def multi = run('Split214ByContainer', [[body: new File('samples/orderful-214-multi-container.json').text, props: [orderfulTransactionId: '1000000002']]])
check(multi.size() == 2, 'multi-container 214 -> one document per container')
check(multi*.json*.containerNumber == ['TSTU1234560', 'TSTU7654321'], 'each document has its own container')
check(multi*.json*.statusCode == ['C2', 'AF'], 'each container keeps its own latest status')
check(multi.every { it.ddp('orderfulTransactionId') == '1000000002' }, 'split documents keep the Orderful transaction id')

def bad = run('Split214ByContainer', [[body: '{"transactionSets":[{"beginningSegmentForTransportationCarrierShipmentStatusMessage":[{}]}]}', props: [orderfulTransactionId: '1000000003']]])
check(bad.isEmpty() && results()[0].result == 'INVALID', 'invalid 214 recorded as INVALID, not output')

// ---- 2. Lookup + cache (Branch 1) --------------------------------------------------------
def containerDocs = multi + [[body: JsonOutput.toJson([orderfulTransactionId: '1000000004', bol: 'NOTINNS0000001',
        containerNumber: 'ABCU0000001', statusCode: 'AF', statusDateTime: '2026-08-25T10:15:00', statusDate: '2026-08-25'])]]
def lookups = run('BuildInboundShipmentLookup', containerDocs.collect { [body: it.body] })
check(lookups.size() == 1, 'one SuiteQL query for the whole batch')
check(lookups[0].json.q.contains("IN ('OCEANBL0000001', 'NOTINNS0000001')"), 'query covers each distinct B10-02 once')

def cacheDocs = run('CacheShipmentsByBol', [[body: new File('samples/suiteql-response.json').text]])
check(cacheDocs*.json*.bol == ['OCEANBL0000001', 'OCEANBL0000099'], 'one cache document per B10-02 found')
check(cacheDocs[0].json.shipmentCount == 2, 'all inbound shipments for the B10-02 are cached together')
Map cache = cacheDocs.collectEntries { [(it.json.bol): it.json.shipmentsJson] }  // Document Cache, index BOL

try { run('CacheShipmentsByBol', [[body: '{"hasMore":true,"totalResults":5000,"items":[]}']]); check(false, 'should throw') }
catch (IllegalStateException e) { check(true, 'paged SuiteQL result fails loudly') }

// ---- 3. Build updates (Branch 2) ---------------------------------------------------------
reset()
// Set Properties: DDP nsShipmentsJson <- Document Cache lookup by DDP bol
def withCache = containerDocs.collect { d ->
    Map j = json.parseText(d.body)
    [body: d.body, props: [bol: j.bol, orderfulTransactionId: j.orderfulTransactionId] + (cache[j.bol] ? [nsShipmentsJson: cache[j.bol]] : [:])]
}
def patches = run('BuildNetSuitePatches', withCache)
check(patches.size() == 2, 'one PATCH per matched inbound shipment')
def p1 = patches.find { it.ddp('nsRecordId') == '4521' }, p2 = patches.find { it.ddp('nsRecordId') == '4522' }
check(p1.json.custrecord_tnc_ib_empty_return_dt == '2026-08-24' && p1.json.shipmentStatus == null, 'C2 -> empty return date, status unchanged')
check(p2.json.shipmentStatus.id == 'inTransit' && p2.json.actualShippingDate == '2026-08-25', 'AF -> inTransit + actual shipping date')
check(p2.json.custrecord_tnc_ib_edi_status == 'AF/NS' && p2.json.custrecord_tnc_ib_edi_carrier == 'CARR', 'status and carrier custom fields mapped')
check(p2.ddp('nsShipmentNumber') == 'INBSHIP1002', 'shipment number carried for the log/email')
def nf = results().find { it.result == 'NOT_FOUND' }
check(nf?.message == 'Container: NOTINNS0000001 not in netsuite', 'not found -> "Container: <no> not in netsuite"')

// No container match -> every shipment under the B10-02; older event -> STALE
reset()
def all = run('BuildNetSuitePatches', [[body: JsonOutput.toJson([bol: 'OCEANBL0000001', containerNumber: null, statusCode: 'X6',
        statusDateTime: '2026-08-26T08:00:00', statusDate: '2026-08-26']), props: [nsShipmentsJson: cache['OCEANBL0000001']]]])
check(all*.ddp('nsRecordId') == ['4521', '4522'], 'no container on the 214 -> all shipments under the B10-02')
reset()
def stale = run('BuildNetSuitePatches', [[body: JsonOutput.toJson([bol: 'OCEANBL0000001', containerNumber: 'TSTU1234560', statusCode: 'AF',
        statusDateTime: '2026-08-01T08:00:00', statusDate: '2026-08-01']), props: [nsShipmentsJson: cache['OCEANBL0000001']]]])
check(stale.isEmpty() && results()[0].result == 'STALE', 'older event than NetSuite has -> STALE')
reset()
def single1 = run('BuildNetSuitePatches', [[body: JsonOutput.toJson([bol: 'OCEANBL0000099', containerNumber: 'NEWU1111111', statusCode: 'AG',
        statusDateTime: '2026-09-01T00:00:00', statusDate: '2026-09-01']), props: [nsShipmentsJson: cache['OCEANBL0000099']]]])
check(single1[0].json.externalDocumentNumber == 'NEWU1111111' && single1[0].json.expectedDeliveryDate == '2026-09-01',
      'empty container field filled; AG -> expected delivery date')

// ---- 4. Record update results ------------------------------------------------------------
reset()
def ok = [orderfulTransactionId: '1000000002', bol: 'OCEANBL0000001', nsRecordId: '4522', nsShipmentNumber: 'INBSHIP1002', statusCode: 'AF']
run('RecordUpdateResult', [
    [body: '', props: ok + [httpStatus: '204']],
    [body: '{"title":"Bad Request","o:errorDetails":[{"detail":"Invalid field value inTransit","o:errorCode":"INVALID_FLD_VALUE"}]}',
     props: ok + [nsRecordId: '4521', nsShipmentNumber: 'INBSHIP1001', httpStatus: '400']]])
check(results()*.result == ['UPDATED', 'UPDATE_FAILED'], '2xx -> UPDATED, error -> UPDATE_FAILED')
check(results()[1].message == 'HTTP 400 INVALID_FLD_VALUE Invalid field value inTransit', 'NetSuite error detail logged')

// ---- 5. Acks and emails ------------------------------------------------------------------
List rs = results()
rs << [orderfulTransactionId: '1000000004', bol: 'NOTINNS0000001', result: 'NOT_FOUND', message: 'Container: NOTINNS0000001 not in netsuite']
rs << [orderfulTransactionId: '1000000005', bol: 'OCEANBL0000001', result: 'UPDATED']
ExecutionUtil.dpps.TNC_214_RESULTS = JsonOutput.toJson(rs)
def acks = run('BuildOrderfulAcks', [])
check(acks*.ddp('orderfulTransactionId') == ['1000000004', '1000000005'], 'transaction with a failed update is not acknowledged')

def mails = run('BuildNotificationEmails', [])
check(mails.size() == 1 && mails[0].ddp('emailAudience') == 'USERS', 'completion email to users')
check(mails[0].ddp('emailSubject') == 'Orderful 214 to NetSuite inbound shipments completed: 2 updated, 1 not in NetSuite, 1 failed, 0 skipped (older event), 0 invalid',
      'subject summarises the run')
check(mails[0].body.contains('Container: NOTINNS0000001 not in netsuite'), 'not-found log line in the email')

rs << [orderfulTransactionId: '1000000003', result: 'INVALID', message: '214 missing B10-02 shipment identification number']
ExecutionUtil.dpps.TNC_214_RESULTS = JsonOutput.toJson(rs)
def mails2 = run('BuildNotificationEmails', [])
check(mails2*.ddp('emailAudience') == ['USERS', 'DEVELOPER'], 'invalid 214 -> developer email too')

reset()
check(run('BuildNotificationEmails', []).isEmpty(), 'nothing processed -> no email')
println 'all tests passed'
