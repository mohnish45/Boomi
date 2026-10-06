# Orderful 214 → NetSuite Inbound Shipment header (REST)

Inbound X12 214 (Transportation Carrier Shipment Status) messages from Orderful update the NetSuite **Inbound Shipment** header records for the container. Boomi talks to NetSuite through the REST API: one SuiteQL lookup per batch, then a PATCH per inbound shipment.

Each run does four things:

1. Reads the 214s from Orderful and **splits them into one document per container**.
2. Looks up the B10-02 shipment identification number (e.g. `ZIMUNGB20976113`) in NetSuite.
   - **Not found:** logs `Container: ZIMUNGB20976113 not in netsuite`.
   - **Found:** loads every inbound shipment for that number into a Boomi **Document Cache**.
3. Maps the 214 fields onto each cached inbound shipment and **updates the header**. A failed update is logged.
4. At the end:
   - Emails a **completion summary to the users**.
   - **Emails the Boomi developer** when something errors. That covers a 214 that can't be processed, and any process-level failure caught by the Try/Catch.

## Process: `ORD_214_to_NS_InboundShipment`

Scheduled every 5–15 minutes. Set *Allow simultaneous executions* to off.

```
Start (No Data) ─ Try/Catch ─┬─ Try ─ Orderful GET ─ Split (1 doc / transaction) ─ [S1] Split214ByContainer ─ Branch
                             │                                                                                │
                             │   1 ─ [S2] BuildInboundShipmentLookup ─ NetSuite SuiteQL ─ [S3] CacheShipmentsByBol ─ Add to Cache
                             │   2 ─ Set Properties (cache → nsShipmentsJson) ─ [S4] BuildNetSuitePatches ─ NetSuite PATCH
                             │         ─ Set Properties (HTTP status) ─ [S5] RecordUpdateResult
                             │   3 ─ [S6] BuildOrderfulAcks ─ Orderful acknowledge
                             │   4 ─ [S7] BuildNotificationEmails ─ Route emailAudience ─ USERS ─ Mail (users)
                             │                                                        └ DEVELOPER ─ Mail (developer)
                             └─ Catch ─ Message (error) ─ Mail (developer)
```

A Branch shape runs its paths in order, and each path finishes for every document before the next one starts. So by path 2 the cache is complete, and by paths 3–4 every update has been recorded.

| # | Shape | Configuration |
| --- | --- | --- |
| 1 | **Start: No Data** | Scheduled in Atom Management. |
| 2 | **Try/Catch** | Catch all errors, retry 0. The Catch path is row 17. |
| 3 | **HTTP Client – GET** Orderful | Fetches the 214s waiting for TNC (your poller / polling bucket). Send header `orderful-api-key` from an Environment Extension. |
| 4 | **Data Process – Split Documents** (JSON) | One document per Orderful transaction. Put the Orderful transaction id in DDP `orderfulTransactionId`. If the list only returns ids, add an HTTP GET per id to fetch each message. |
| 5 | **[S1] Data Process** – [`Split214ByContainer.groovy`](scripts/Split214ByContainer.groovy) | One *container status* document per container (see [Splitting](#splitting-by-container)). Sets DDPs `bol` and `containerNumber`. A 214 it can't use is recorded as `INVALID` instead of failing the batch. |
| 6 | **Branch** (4 paths) | |
| 7 | Path 1: **[S2]** [`BuildInboundShipmentLookup.groovy`](scripts/BuildInboundShipmentLookup.groovy) | Builds one SuiteQL query for every distinct B10-02 number in the batch (chunked at 200). |
| 8 | **HTTP Client – POST** SuiteQL | `…/services/rest/query/v1/suiteql?limit=1000`, header `Prefer: transient`. |
| 9 | **[S3]** [`CacheShipmentsByBol.groovy`](scripts/CacheShipmentsByBol.groovy) → **Add to Cache** | Outputs one document per B10-02 found, `{bol, shipmentCount, shipmentsJson}`. Add these to Document Cache **NS Inbound Shipments by BOL**: a JSON profile with those three fields, and index **BOL** on `bol`. |
| 10 | Path 2: **Set Properties** | DDP `nsShipmentsJson` ← *Document Cache* "NS Inbound Shipments by BOL", index BOL, key = DDP `bol`, element `shipmentsJson`. Empty when the number isn't in NetSuite. |
| 11 | **[S4]** [`BuildNetSuitePatches.groovy`](scripts/BuildNetSuitePatches.groovy) | When the cache is empty for the number, logs **`Container: <B10-02> not in netsuite`**. Otherwise outputs one PATCH body per inbound shipment to update, with DDP `nsRecordId`. |
| 12 | **HTTP Client – PATCH** NetSuite | Resource path `services/rest/record/v1/inboundShipment/{1}`, where `{1}` = DDP `nsRecordId`. Turn on **Return Application Error Responses** so a failed update reaches the next step instead of stopping the run. NetSuite returns `204` on success. |
| 13 | **Set Properties** → **[S5]** [`RecordUpdateResult.groovy`](scripts/RecordUpdateResult.groovy) | Set DDP `httpStatus` ← Meta › Base › Application Status Code and `httpMessage` ← Application Status Message. The script logs **`Update failed for inbound shipment …: <NetSuite error>`** on a non-2xx response. |
| 14 | Path 3: **[S6]** [`BuildOrderfulAcks.groovy`](scripts/BuildOrderfulAcks.groovy) → **HTTP Client** Orderful | Acknowledges (removes) each finished transaction in Orderful. A transaction with a failed update stays in Orderful, so the next poll retries it. |
| 15 | Path 4: **[S7]** [`BuildNotificationEmails.groovy`](scripts/BuildNotificationEmails.groovy) → **Route** on DDP `emailAudience` | `USERS` → row 16a. `DEVELOPER` → row 16b. Sends nothing when the run had no 214s. |
| 16a | **Set Properties** → **Mail – Send** (users) | Mail To ← process property `TNC_214_USER_EMAILS` (Environment Extension). Subject ← DDP `emailSubject`. Content type `text/html`. |
| 16b | **Set Properties** → **Mail – Send** (developer) | Mail To ← process property `TNC_BOOMI_DEV_EMAIL`. Subject ← DDP `emailSubject`. |
| 17 | Catch: **Message** → **Mail – Send** (developer) | Subject `ERROR: Orderful 214 to NetSuite failed`. Body includes `{meta: Try/Catch Message}`, the process name, and the execution id. |

The scripts pass results to each other through DPP **`TNC_214_RESULTS`**, a JSON list with one entry per container or shipment. Each entry's `result` is one of:

- `UPDATED`
- `UPDATE_FAILED`
- `NOT_FOUND`
- `STALE` (an older event than the one NetSuite already has)
- `INVALID`

The acks and both emails are built from that list.

> **DDPs across the PATCH call:** [S5] reads `nsRecordId`, `bol` and the other DDPs from the HTTP Client response document. If your runtime doesn't carry user DDPs through the connector, add a Flow Control (*run each document individually*) before row 12. Then copy those DDPs to DPPs with a Set Properties shape and read them from there.

### Splitting by container

A 214 can describe several containers in two ways: several transaction sets, or several LX/AT7 loops, each with its own MS2. Each AT7 status event is assigned to a container in this order:

1. The **MS2** in its own AT7 loop: owner + number + check digit, e.g. `TSTU` + `123456` + `0`.
2. Otherwise, the **L11\*EQ** in its LX loop.
3. Otherwise, the transaction set's only container.

Each container gets one document carrying its **latest** event. An event with no container at all becomes a document with no container number, and it updates every inbound shipment under the B10-02 number.

### Which inbound shipments are updated

The lookup returns every inbound shipment whose bill of lading equals B10-02. For a container document, [S4] updates the shipment(s) whose container number matches. If none match, or the document has no container number, it updates **all** inbound shipments under that B10-02 number.

## Field mapping (214 → Inbound Shipment header)

| 214 | NetSuite field (REST id) | Rule |
| --- | --- | --- |
| B10-02 `shipmentIdentificationNumber` | *lookup:* `billOfLading` | Match key for the SuiteQL lookup and the cache. |
| L11\*EQ or MS2-01+02+03 | *match:* `externalDocumentNumber` | Picks the shipment under the B10-02. Filled in when it's empty and the number has exactly one shipment. |
| AT7-01 ∈ AF, X6, CD, P1, AM, AV, I1, OA | `shipmentStatus` = `inTransit` | Only from `toBeShipped`. A 214 never sets `received`, because receiving in NetSuite creates the item receipts. |
| AT7-01 = AF, AT7-05 | `actualShippingDate` | Departed pickup. |
| AT7-01 ∈ AG, AB, AT7-05 | `expectedDeliveryDate` | Estimated delivery / delivery appointment. |
| AT7-01 ∈ X1, D1, AT7-05 | `actualDeliveryDate` | Arrived / unloaded at destination. |
| AT7-01 = C2, AT7-05 | `custrecord_tnc_ib_empty_return_dt` | Delivered empty (container returned). Confirm the meaning with the carrier. |
| AT7-01 / AT7-02 | `custrecord_tnc_ib_edi_status` | For example `AF/NS`. |
| AT7-05/06/07 → ISO-8601 | `custrecord_tnc_ib_edi_status_dt` | Free-Form Text. Also used to skip out-of-order (older) 214s. |
| MS1 of the latest AT7 loop | `custrecord_tnc_ib_edi_location` | City, state, country. |
| B10-03 SCAC | `custrecord_tnc_ib_edi_carrier` | |
| B10-01 | `custrecord_tnc_ib_edi_carrier_ref` | The carrier's load reference. |
| Orderful transaction id | `custrecord_tnc_ib_orderful_txn` | Audit trail back to Orderful. |

To change the rules, edit the constants at the top of `BuildNetSuitePatches.groovy`: `IN_TRANSIT_CODES`, `DATE_FIELD_BY_CODE`, and the `F_*` field ids.

## Connections

- **NetSuite (HTTP Client):** `https://<ACCOUNT>.suitetalk.api.netsuite.com`.
  - **Authentication:** Token-Based Authentication (OAuth 1.0a, HMAC-SHA256, Realm = account id, e.g. `1234567_SB1`).
  - **Role permissions:** REST Web Services, Log in using Access Tokens, SuiteAnalytics Workbook (for SuiteQL), Inbound Shipment (Edit), and the custom fields above.
  - Keep the account id and all keys/secrets in Environment Extensions.
- **Orderful (HTTP Client):** `https://api.orderful.com`, with the API key from an Environment Extension. Look up the poll and acknowledge endpoints for your Orderful integration in Orderful's API reference.
- **Mail:** your SMTP connection. Recipients come from process properties `TNC_214_USER_EMAILS` and `TNC_BOOMI_DEV_EMAIL`, set per environment.

## Do you need a Boomi Queue?

No. Orderful keeps every 214 until this process acknowledges it (path 3). That makes Orderful the buffer and the place to replay from, so a queue would only add a second one. Consider a queue only if you switch to Orderful pushing to a Boomi listener and see timeouts or NetSuite concurrency errors.

## Before going live: confirm these

1. **The NetSuite field that holds B10-02.** The default is the native `billoflading` (`NS_BOL_FIELD` in `BuildInboundShipmentLookup.groovy`).
2. **The field that holds the container number.** The default is `externaldocumentnumber` (`NS_CONTAINER_FIELD` there, and `F_CONTAINER` in `BuildNetSuitePatches.groovy`).
3. **The custom field ids.** The `custrecord_tnc_ib_*` names are placeholders. Create the fields or point the constants at existing ones. `…_status_dt` must be Free-Form Text.
4. **The carrier's AT7 code list.** In particular, confirm that `C2` means *delivered empty* for this carrier.
5. **Not-found handling.** `ACK_NOT_FOUND` in `BuildOrderfulAcks.groovy` is `true` by default, so the 214 is logged, emailed, and acknowledged. Set it to `false` to keep retrying until the inbound shipment exists.
6. **Email for failed updates.** Failed updates go to the users' email. Set `DEVELOPER_ON_UPDATE_FAILURE = true` in `BuildNotificationEmails.groovy` to copy the developer as well.

## Samples and tests

- `samples/orderful-214.json` is a real Orderful 214 with its identifiers anonymized.
- `samples/orderful-214-multi-container.json` has two containers.
- `samples/suiteql-response.json` is a lookup result.

`test/run-tests.groovy` runs all seven scripts in process order and simulates the HTTP, cache and Set Properties shapes in between:

```
groovy -cp test/stubs test/run-tests.groovy
```
