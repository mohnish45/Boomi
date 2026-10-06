# CUST01 – Single-package cartonization (Scale WMS) sample data

Goal: for company **CUST01**, every order ships **complete in one carrier package**.
The package is a **poly mailer** when every item is soft goods, and a **corrugated box**
otherwise. In both cases it's the smallest container that fits the whole order.
One package per order cuts parcel charges (one base rate, and less DIM-weight
inflation) and removes multi-carton pack and label work.

## Files

| File | What it is |
| --- | --- |
| `carton_types.csv` | Container master: 3 poly mailers and 4 boxes, with inside dims, fill %, max weight, tare, outside (ship) dims and select priority. |
| `item_master.csv` | 10 TNC-style SKUs with each-level dims and weight, plus `packaging_class` (`POLY_OK` / `BOX_ONLY`), fragile flag, and carton group. |
| `orders.csv` | Three multi-line test orders in flat form. |
| `scale_shipment_download.xml` | The same three orders as a SCALE Shipment interface download (`WMWROOT` / `Shipments`). |
| `expected_cartonization.csv` | Expected container per order, with freight comparison against one package per line. |
| `cartonize_check.py` | Reference implementation of the rules below. Rerun it after changing the data. |

## Selection rules

1. **Explode** the shipment into eaches. Total cube = Σ qty × L × W × H; total weight = Σ qty × weight.
2. **Packaging class**: if any item is `BOX_ONLY` (fragile, rigid or crushable, e.g. ceramic, memory foam), only boxes are candidates. Otherwise poly mailers are tried first.
3. **Pick the first container by `select_priority`** that passes all three checks:
   - **Dimensional fit**: every item's sorted dims are ≤ the container's sorted inside dims (longest to longest).
   - **Cube**: total cube ≤ inside cube × `max_fill_pct` (90% poly, 85% box).
   - **Weight**: total weight + tare ≤ `max_weight_lb`.
4. **No single container fits** → don't split silently. Flag the shipment
   (`EXCEPTION_NO_SINGLE_CARTON_FITS`) for standard multi-carton cartonization or a
   supervisor decision.

This is a cube heuristic, not true 3-D bin packing, which matches how SCALE
cartonizes. Fill % is the safety margin, so tune it after the first live waves.

## Test orders and expected result

| Order | Contents | Expected | Billable lb (single pkg) | Baseline: 1 pkg per line |
| --- | --- | --- | --- | --- |
| SO-100245 | 5 lines / 6 units: towels, dish cloths, oven mitt, pot holders, table runner | **PB-M poly mailer**, 73% full, 3.0 lb gross | **8** | 5 pkgs, 25 lb |
| SO-100246 | 6 lines / 7 units: same soft goods **plus ceramic soap dispenser and foam bath mat** | **BX-M box** (the ceramic forces a box), 80.5% full, 7.25 lb gross | **12** | 6 pkgs, 32 lb |
| SO-100247 | Rolled 24x36 rug (25" long) + towels | **Exception**: rug is longer than BX-XL inside length (23.75") | n/a | n/a |

Billable weight = max(actual lb, L×W×H / 139) on outside dims, rounded up (UPS/FedEx
daily-rate divisor). Check this against your contracted divisor.

## Scale WMS configuration checklist for CUST01

Menu names differ slightly between SCALE versions; the objects are the same.

1. **Item UOM dims**: populate L/W/H/weight on the EA (and any pack) UOM for every
   CUST01 item. Cartonization is only as good as this data, and items with zero dims
   will be under-cartonized.
2. **Container (carton) types**: create the 7 types in `carton_types.csv`, with
   inside dims, max weight, tare and fill %.
3. **Carton group**: one group, `CUST01-SINGLE`, holding all 7 types, assigned to every
   CUST01 item. Put poly and box types in the **same** group. SCALE cartonizes per
   carton group, so separate poly and box groups would split mixed orders into two
   packages, which defeats the goal.
4. **Poly vs box eligibility**: SCALE has no native "poly OK" flag. Choose one:
   - **(Recommended)** Pre-select the container in Boomi using these rules and send it
     on the shipment (a UserDef field, or the field your pack station or parcel
     manifest reads). SCALE then cartonizes into that one container type.
   - Keep `packaging_class` in an item UserDef and enforce it with a custom
     cartonization rule or pack-station validation in SCALE.
5. **Wave flow / cartonization step** for CUST01 ecom orders: cartonize by
   **shipment**, mixed items allowed, method **cube + weight**, **smallest container
   first**.
6. **Ship complete**: `ShipComplete = Y` on the shipment (set in the sample XML) and
   allocation that won't release partials. Otherwise backorders create a second package.
7. **Parcel manifest**: make sure the container's **outside** dims and gross weight flow
   to the carrier label request so DIM weight is rated correctly.

## Known gaps in the item data to fix before go-live

- `TNC-PM-4PK-LIN` (placemats, 19×13 flat) only fits PB-L or BX-L. Any order that
  includes it jumps two container sizes. Consider a 20×14×4 box, or folding the
  placemats at pack-out.
- `TNC-RG-2436-NAT` (rolled rug, 25") fits no current container. Either add a long
  tube or box (e.g. 26×8×8) or set it as ship-alone.

## Before loading into SCALE

- Check the XML element names against the Shipment interface XSD for your SCALE version.
- Replace `WH01`, the UPS/GND carrier and service codes, and the `C1004x` customer IDs
  with real CUST01 values.
