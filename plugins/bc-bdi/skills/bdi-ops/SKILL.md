---
description: Help design or operate Boomi Data Integration (BDI) batch/ETL pipelines - large-volume database, data lake, and warehouse loads. Use when the user mentions bulk/batch data movement, ETL, data warehouse loads, or large-scale database sync on the Boomi platform.
---

# Boomi Data Integration (BDI)

Boomi Data Integration covers batch-oriented, high-volume data movement (ETL/ELT) as distinct from event-driven, document-at-a-time integration processes — think nightly warehouse loads, large table syncs, and data lake ingestion rather than a single API call per transaction.

## Design guidance specific to batch/ETL

- **Batch sizing**: tune connector batch size (rows per Get/Send call) to the source/target's throughput characteristics — too large risks timeouts and memory pressure on the Atom, too small multiplies round-trip overhead. Start from the connector's documented default and adjust based on observed execution time, not a guess.
- **Incremental vs full loads**: prefer incremental extraction (watermark column, CDC, or a "last modified since" filter) over full reloads for large tables; confirm the source table has a reliable modified-timestamp or change-tracking mechanism before designing around one.
- **Staging**: for complex transforms or multi-target loads, stage extracted data (Disk, Database staging table) rather than transforming in a single long pipeline — this makes retries and partial-failure recovery much simpler than re-running the full extract.
- **Idempotency on load**: use Upsert (not blind Insert) into target tables/warehouses so a re-run after a partial failure doesn't create duplicates.
- **Parallelism**: Boomi processes can run multiple executions concurrently (schedule + concurrent execution limits, or explicit sharding by key range); confirm the target system can actually accept concurrent writes before parallelizing a load that was previously serial.

## Common operational issues

- **Slow batch jobs**: check whether the bottleneck is the source query (missing index, unfiltered full scan), the network hop for large payloads, or a Data Process script doing per-row work that could be vectorized/pushed into SQL instead.
- **Partial failures on large batches**: confirm whether the connector operation fails the whole batch or continues per-row on error — this changes whether you need row-level error capture (Exception path + logging) to know what actually landed.
- **Warehouse-specific loads**: bulk-load APIs (e.g. Snowflake, Redshift, BigQuery connectors) usually expect data staged in cloud storage first rather than row-by-row inserts — check the specific connector's documented load pattern before assuming a generic Database connector will perform adequately at scale.

Ask about source/target systems, expected volume, and current run time before recommending specific batch-size or architecture changes.
