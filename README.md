# Boomi Companion

A Claude Code plugin marketplace for building, operating, and automating on the [Boomi](https://boomi.com) integration platform.

> Community project. Not officially supported by Boomi; provided as-is with no SLA.

## Install

```
/plugin marketplace add mohnish45/Boomi
```

Then install any of the plugins below, e.g.:

```
/plugin install bc-integration@boomi-companion
```

## Plugins

| Plugin | Focus |
| --- | --- |
| `bc-integration` | Process design, Groovy/JavaScript Data Process scripting, EDI/profile mapping, and AtomSphere (platform) API automation. |
| `bc-marketplace` | Finding and evaluating prebuilt Boomi Marketplace recipes and connectors. |
| `bc-datahub` | Boomi Data Hub (MDM): models, sources, match/merge rules, golden records. |
| `bc-bdi` | Boomi Data Integration: batch/ETL pipelines, database and warehouse loads. |
| `bc-agentstudio` | Boomi Agentstudio: agents, tools, and knowledge bases wired into Boomi processes. |

Each plugin ships as Agent Skills that Claude loads automatically when the conversation matches its domain — no explicit invocation needed.

## Integrations

Reference integrations built with these skills live under `integrations/`:

| Integration | Summary |
| --- | --- |
| [`orderful-214-netsuite-shipment`](integrations/orderful-214-netsuite-shipment) | Orderful X12 214 shipment status → NetSuite Inbound Shipment (container) header via REST (SuiteQL lookup + PATCH). |

## Repository layout

```
.claude-plugin/marketplace.json   # marketplace catalog
plugins/
  bc-integration/
  bc-marketplace/
  bc-datahub/
  bc-bdi/
  bc-agentstudio/
integrations/
  orderful-214-netsuite-shipment/
```

## Contributing

Skills live under `plugins/<plugin-name>/skills/<skill-name>/SKILL.md`. Keep skill descriptions specific enough that Claude picks the right skill for the task, and keep guidance concrete (concepts, common pitfalls, and what to ask the user) rather than generic advice.
