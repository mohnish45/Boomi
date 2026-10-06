# Orderful 214 → NetSuite Container/Shipment header (REST)

Inbound X12 214 (Transportation Carrier Shipment Status) messages from Orderful update the matching container/shipment header in NetSuite. Boomi calls the NetSuite REST API (SuiteQL lookup, then record PATCH).

```
Orderful ──214 JSON──▶ Boomi ──SuiteQL lookup──▶ NetSuite
                         │◀────── id, status ───────┘
                         ├──PATCH inboundShipment/{id}──▶ NetSuite
                         └──ack / fail delivery──▶ Orderful
```

## Do you need a Boomi Queue (Atom Queue)?

**No, not for this flow.** Orderful already holds each transaction until you confirm you've processed it, so it acts as your durable buffer and replay source. An Atom Queue between Orderful and the NetSuite update gives you a second copy of the same buffer to monitor, purge, and redeliver from.

| Orderful delivery method | Recommendation |
| --- | --- |
| **Poller / polling bucket** (Boomi pulls on a schedule) | **Drop the queue.** Fetch the 214s, process each one, and acknowledge/remove it from the bucket only after NetSuite returns 2xx. Failures stay in the bucket and get retried on the next poll. |
| **Webhook / HTTP push** to a Boomi Web Services Server listener, with NetSuite slow or rate-limited | **A queue is reasonable.** The listener writes to the queue and returns 200 quickly; a second process reads from the queue and calls NetSuite. You get retries and a dead-letter queue. Only add this if you see webhook timeouts or NetSuite concurrency errors (`429` / `CONCURRENCY_LIMIT_EXCEEDED`). |
| Webhook at normal 214 volume | No queue. Process synchronously and return non-2xx on failure so Orderful marks the delivery failed and you can resend it from Orderful. |

If you keep the queue you already built, three things matter:

1. Acknowledge Orderful only after the message is **committed to the queue**, not when it's read from Orderful.
2. Configure the queue's **retry count and dead-letter queue**, and have someone watch the DLQ (Atom Management → Queue Management).
3. Atom Queues live on **one runtime**. Messages sit on that Atom/Molecule's disk, so they aren't shared across environments and don't move with a redeploy.

Two of these options are simpler: polling without a queue, or a webhook without a queue. Either one leaves you with one buffer (Orderful) and one place to replay from.

## Process design (polling, no queue)

Process `ORD_214_to_NS_InboundShipment`, scheduled every 5–15 minutes. Turn on **"Allow simultaneous executions" = off** so two polls can't process the same 214.

| # | Shape | Configuration |
| --- | --- | --- |
| 1 | Start: **No Data** | Schedule in Atom Management. |
| 2 | **HTTP Client – GET** Orderful | Read the 214s waiting for TNC (polling bucket / transactions endpoint for your Orderful integration). Header `orderful-api-key` from an Environment Extension. |
| 3 | **Data Process – Split Documents** (JSON) | One document per transaction. Put the Orderful transaction id in DDP `orderfulTransactionId`. If the list only returns ids, add an HTTP GET per id to fetch the message body. |
| 4 | **Flow Control** | *Run each document individually*. The scripts pass data in a Dynamic Process Property, so documents must run one at a time. |
| 5 | **Try/Catch** | Wrap 6–11. Catch path → step 12. |
| 6 | **Data Process – Custom Scripting** | [`scripts/Normalize214.groovy`](scripts/Normalize214.groovy). Picks the latest AT7 event, the container (L11*EQ or MS2) and the ocean BOL (L11*BM or B10-02), and outputs the SuiteQL lookup body. |
| 7 | **HTTP Client – POST** NetSuite SuiteQL | `https://<ACCOUNT>.suitetalk.api.netsuite.com/services/rest/query/v1/suiteql`, header `Prefer: transient`, content type `application/json`. |
| 8 | **Data Process – Custom Scripting** | [`scripts/BuildNetSuitePatch.groovy`](scripts/BuildNetSuitePatch.groovy). Sets DDP `nsAction` and `nsRecordId` and outputs the PATCH body. |
| 9 | **Route** on DDP `nsAction` | `PATCH` → 10 · `STALE` → 11 (nothing to update, ack it) · `NOT_FOUND` / `AMBIGUOUS` → Exception shape (goes to the catch path). |
| 10 | **HTTP Client – PATCH** NetSuite | Resource path `services/rest/record/v1/inboundShipment/{1}`, replacement variable = DDP `nsRecordId`. NetSuite returns `204 No Content` on success. |
| 11 | **HTTP Client – POST** Orderful acknowledge | Remove the transaction from the bucket, or approve the delivery. |
| 12 | Catch: **Notify** + Orderful *fail delivery* | Log the error and the canonical JSON (DPP `TNC_214_CANONICAL`). Leave the 214 unacknowledged, or mark it failed, so it can be replayed. Optionally email the logistics team on `NOT_FOUND`, because that usually means the container hasn't been created in NetSuite yet. |

### NetSuite REST connection

- **Authentication:** Token-Based Authentication (OAuth 1.0a, HMAC-SHA256) with an integration record and an access token for a dedicated integration role. In the Boomi HTTP Client connection, choose the OAuth (1.0) auth type with signature method HMAC-SHA256 and **Realm = account id** (for example `1234567_SB1` for a sandbox). If your runtime's HTTP Client can't sign with SHA256, generate the `Authorization` header in a script instead. OAuth 2.0 client credentials (certificate/JWT) is the other supported option.
- **Role permissions:** REST Web Services, Log in using Access Tokens, SuiteAnalytics Workbook (required for SuiteQL), Inbound Shipment (Edit), and access to the custom fields below.
- Put the account id, consumer key/secret, and token id/secret in **Environment Extensions**, never in the component.

### Orderful connection

- HTTP Client connection to `https://api.orderful.com`, with the API key header from an Environment Extension.
- Look up the exact poll, acknowledge, and fail endpoints for your Orderful integration type in Orderful's API reference (Orderful UI → Settings → API). The process shape above stays the same whichever endpoints you use.

## Field mapping

The 214 doesn't need a Boomi Map component. `Normalize214.groovy` flattens the Orderful JSON, and `BuildNetSuitePatch.groovy` applies these rules:

| 214 source | NetSuite Inbound Shipment field (REST id) | Rule |
| --- | --- | --- |
| L11-01 where L11-02 = `EQ`; otherwise MS2-01 + MS2-02 + MS2-03 (owner + number + check digit) | *lookup:* `externalDocumentNumber` (configurable) | Match key: container number, uppercased, `[A-Z0-9-]` only. |
| L11-01 where L11-02 = `BM`/`MB`; otherwise B10-02 | *lookup fallback:* `billOfLading`, and written back | Second match key: the ocean bill of lading. |
| AT7-01 ∈ AF, X6, CD, P1, AM, AV, I1, OA | `shipmentStatus` = `inTransit` | Only from `toBeShipped`. A shipment that's already partially received, received, or closed is never changed. |
| AT7-01 = AF, AT7-05 | `actualShippingDate` | Departed pickup. |
| AT7-01 ∈ AG, AB, AT7-05 | `expectedDeliveryDate` | Estimated delivery / delivery appointment. |
| AT7-01 ∈ X1, D1, AT7-05 | `actualDeliveryDate` | Arrived / unloaded. Receiving stays a warehouse step in NetSuite: a 214 never sets `received`, because that is what creates item receipts. |
| AT7-01 / AT7-02 | `custrecord_tnc_ib_edi_status` | For example `AF/NS`. |
| AT7-05/06/07 → ISO-8601 | `custrecord_tnc_ib_edi_status_dt` | Free-Form Text. Also used to skip out-of-order (older) 214s. |
| MS1-01/02/03 of the latest AT7 loop (not the N1 parties) | `custrecord_tnc_ib_edi_location` | City, state, country. |
| Orderful transaction id | `custrecord_tnc_ib_orderful_txn` | Audit trail back to Orderful. |

When several AT7 events arrive in one 214, only the **latest** event (by date/time) is applied.

## Before going live: confirm these

1. **Which NetSuite record is the "container/shipment header".** The scripts target the native **Inbound Shipment** (`inboundshipment` / `inboundShipment`). If TNC uses a custom container record instead, change `NS_RECORD_TABLE` in `Normalize214.groovy` and the PATCH path to `services/rest/record/v1/customrecord_<id>/{1}`, then rename the fields in `BuildNetSuitePatch.groovy`.
2. **Which field holds the container number** (`NS_CONTAINER_FIELD`, default `externaldocumentnumber`).
3. **The custom field script ids** (`custrecord_tnc_ib_*` are placeholders). Create them, or point the constants at existing fields.
4. **Which AT7 codes your carriers actually send.** Adjust the code lists at the top of `BuildNetSuitePatch.groovy`. A real drayage 214 sent `C2`, which isn't mapped yet. Until it's mapped, a `C2` only updates the last-status custom fields.

The Orderful element names are confirmed against a real Orderful 214: AT7-01 is `shipmentStatusIndicatorCode`, MS2 is `equipmentOrContainerOwnerAndType`, and MS1 is `equipmentShipmentOrRealPropertyLocation`.

## Testing locally

The scripts run outside Boomi against the files in [`samples/`](samples), using a stub `ExecutionUtil`:

```
groovy -cp test/stubs test/run-tests.groovy
```

In Boomi, test with Test Mode on a non-production Atom against a NetSuite sandbox account. Check the execution log for the `214 <code> container=… : PATCH|STALE|NOT_FOUND` line that each document writes.
