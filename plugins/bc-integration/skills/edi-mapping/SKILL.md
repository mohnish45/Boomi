---
description: Design or debug Boomi profiles and maps, including EDI (X12/EDIFACT), flat file, XML, and JSON profiles, and the Map component that transforms between them. Use when the user mentions profiles, maps, segments/elements, EDI envelopes (ISA/GS/ST), or transformation functions.
---

# Boomi profiles and maps

## Profiles

A profile describes the shape of a document so Boomi can parse and generate it:
- **XML profile**: built from an XSD or by example; supports namespaces and repeating elements.
- **JSON profile**: built from a JSON schema or by example.
- **Flat File profile**: fixed-width or delimited; define record types, field positions/delimiters, and how records nest (header/detail/trailer).
- **EDI profile (X12 / EDIFACT / HL7 / etc.)**: modeled on the standard's segment/element structure. X12 documents are wrapped in ISA (interchange) / GS (functional group) / ST (transaction set) envelopes — the EDI profile setup on the connector or Start shape controls how envelopes are split into individual transactions before mapping.
- **Database / Web Services profiles**: generated from a table/stored procedure or a WSDL/OpenAPI definition.

## Maps

A Map component transforms one source profile into one target profile:
- **Field-to-field mapping**: direct copy, optionally through a **function** (string concat/substring, date parsing/formatting, math, lookup/cross-reference table, custom scripting function in Groovy/JS).
- **Cross-reference tables**: use for code translation (e.g. customer's item codes → internal SKUs) instead of embedding lookup logic in scripts.
- **Default values**: set on unmapped required target fields to avoid validation failures downstream.
- **Multiple maps in one process**: common pattern is Map → Business Rules/Decision → Map again when the transformation branches by document type.

## EDI-specific guidance

- Confirm the **envelope identifiers** (ISA06/08 sender/receiver IDs, GS02/03) match what the trading partner expects — mismatches are the most common cause of a trading partner rejecting an interchange.
- Validate against the correct **version/release** (e.g. X12 004010 vs 005010) — segment and element definitions differ between versions.
- Use **acknowledgments** (997/999 for X12) as a separate inbound process rather than assuming delivery succeeded just because the outbound send didn't error.
- For outbound EDI, generate through the EDI profile's document generation (don't hand-build segments in a script) so control characters, segment terminators, and envelope counts stay consistent.
- When debugging a rejected EDI file, get the raw interchange from Process Reporting's document tracking and check envelope-level fields before assuming the transaction-set body mapping is wrong.

## Reviewing a map

When asked to review or build a map: confirm source and target profile field names/paths first (ask for the profile export or a sample document if not provided), identify any fields needing a cross-reference table rather than a script function, and flag target-required fields left unmapped with no default.
