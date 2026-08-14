---
description: Design, review, or refactor Boomi AtomSphere integration processes (the shape-based flows built in the Process Designer). Use when the user is planning a new integration, asking which shapes/connectors to use, reviewing an exported process XML, or debugging why a deployed process misbehaves.
---

# Boomi process design

Help the user design or review Boomi integration processes built from shapes in the AtomSphere Process Canvas.

## Core building blocks

- **Start shape**: Connector (polling/listener), AS2/API, or "No Data" for manual/sub-process triggers.
- **Connector shapes**: `Get`, `Send`, `Query`, `Listen`, `Create/Update/Upsert/Delete` operations against a configured connection + operation component. Common connectors: HTTP Client, Web Services Server/Client (SOAP), Database, Disk, FTP/SFTP, Mail, Salesforce, NetSuite, EDI (X12/EDIFACT).
- **Document flow shapes**: `Map` (profile-to-profile transform), `Decision`, `Branch`, `Route`, `Business Rules`, `Data Process` (Groovy/JS scripting, combine/split documents, PGP, archive), `Message`, `Notify`, `Set Properties`, `Exception`, `Try/Catch`, `Stop`, `Return Documents`, `Sub-Process`.
- **Profiles**: define document shape — XML, JSON, Flat File, Database, EDI, XML/JSON via WSDL or OpenAPI. A Map always transforms one profile to another.
- **Properties**: Dynamic Process Properties (DPP, per-execution) vs Dynamic Document Properties (DDP, per-document) vs Process Properties (component-scoped defaults). Don't confuse these when debugging — DPPs travel with the whole execution, DDPs with a single document.
- **Runtimes**: Atom (single node), Molecule (clustered Atom), Atom Cloud (Boomi-hosted). Deployment targets an Environment (e.g. Test/Prod) via Packaged Components; connection values are overridden per-environment with Environment Extensions rather than hardcoded per environment.

## Design guidance

- Default to one process per logical integration flow; extract shared logic into Sub-Processes rather than duplicating shapes.
- Put error handling around volatile shapes (connector calls, scripts) with `Try/Catch` and route failures to a `Notify`/logging path — don't let one bad document silently stop a batch when the connector supports per-document error handling.
- Use `Business Rules`/`Decision` for simple conditional logic; reserve `Data Process` scripting for logic that can't be expressed declaratively (custom parsing, complex looping, external library calls).
- When batching, understand whether the connector operation is document-at-a-time or supports batching (many DB/API operations do); mismatched batch settings are a common cause of throttling or partial failures.
- Favor Environment Extensions over per-environment components so the same packaged process promotes unchanged from Test to Prod.

## Reviewing an exported process

Boomi components export as XML (`<bns:Component>` / `<process>` elements referencing shape `configuration` blocks). When reviewing an export:
1. Identify the Start shape and trace the primary document path before branches.
2. Check every connector shape's operation type (Get/Send/Query/etc.) against its parent connection.
3. Look for shapes with no error path (no surrounding Try/Catch and no Exception shape downstream) on anything that calls an external system.
4. Flag hardcoded connection values (host, credentials, paths) that should be Environment Extensions instead.
