---
description: Help design or operate Boomi Data Hub (MDM) - models, sources, match/merge rules, and golden record governance. Use when the user mentions Data Hub, golden records, match rules, or master data management on the Boomi platform.
---

# Boomi Data Hub

Boomi Data Hub is Boomi's master data management (MDM) service: it holds a canonical **model** for an entity (e.g. Customer, Product), receives records from one or more **sources** via integration processes, and produces a **golden record** by matching and merging incoming records.

## Core concepts

- **Model**: defines the entity's fields, structure, and which fields participate in matching. Models are versioned; changing field structure on a live model requires care around existing sourced records.
- **Source**: a system of record connected via a Boomi integration process that pushes records into the model (via the Data Hub connector's `Upsert`/`Source Upsert` operation) and/or receives golden records back via `Query`/subscription.
- **Match rules**: define which fields (and fuzzy-match tolerance) determine whether two incoming records represent the same real-world entity. Poorly tuned match rules are the most common cause of duplicate or incorrectly merged golden records.
- **Merge/survivorship rules**: for matched records, define which source "wins" per field (e.g. by source priority, recency, or explicit field-level survivorship).
- **Golden record**: the merged, canonical output record, which can be queried or subscribed to by downstream systems.

## Common operational tasks

- **Adding a new source system**: build a Boomi process using the Data Hub connector's Upsert operation, mapping the source's native profile into the model's schema; confirm the source's priority/trust ranking against existing sources before go-live so survivorship behaves as expected.
- **Debugging an unexpected merge**: check match rule field weights/thresholds first, then confirm the incoming records' field values actually match on the configured match fields (a mismatch is often a mapping bug feeding the wrong field into a match-relevant attribute).
- **Debugging a missing/duplicate golden record**: check whether the source process is sending Upserts with a consistent source-record ID — inconsistent IDs across executions cause the same real-world entity to be treated as new each time.
- **Publishing golden records downstream**: use a Data Hub connector `Query` (pull) or configure the model's outbound subscription (push) to a target system, rather than re-deriving golden-record logic in a separate process.

Ask which model and which source/target systems are involved before proposing a specific fix, since match/merge behavior is model-specific configuration, not a platform default.
