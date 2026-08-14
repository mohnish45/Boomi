---
description: Automate Boomi account/platform operations via the AtomSphere API (deploying packages, managing environments, querying execution records, extensions, Atoms). Use when the user wants a script or API call against platform.boomi.com/api/rest/v1, mentions the AtomSphere API, or wants to automate deployment/monitoring outside the AtomSphere UI.
---

# AtomSphere (platform) API

The AtomSphere API lets you automate account administration — it is not for building integration processes, only for managing them.

## Basics

- Base URL: `https://api.boomi.com/api/rest/v1/<accountId>/<ObjectType>`
- Auth: HTTP Basic Auth using either a user's platform credentials or an **API token** (`BOOMI_TOKEN.<username>` as the username, the token as the password) — prefer an API token over a personal password for automation.
- Content negotiation: supports both XML and JSON; set `Accept`/`Content-Type` accordingly. JSON is usually simpler for scripting.
- Object model follows the platform's own objects: `Component`, `ComponentMetadata`, `PackagedComponent`, `Deployment`, `Environment`, `EnvironmentExtensions`, `Atom`, `ExecutionRecord`, `ExecutionSummaryRecord`, `Process`, `ProcessSchedules`, `Account`, `Role`, `User`, etc. Each supports a subset of `CREATE`/`UPDATE`/`GET`/`DELETE`/`QUERY`/`bulk` operations — check which the specific object supports before assuming full CRUD.

## Common automation patterns

**Query recent execution failures:**
```
POST /Query/ExecutionRecord
{
  "QueryFilter": {
    "expression": {
      "operator": "and",
      "nestedExpression": [
        {"argument": ["ERROR"], "operator": "EQUALS", "property": "status"},
        {"argument": ["<processId>"], "operator": "EQUALS", "property": "processId"}
      ]
    }
  }
}
```

**Deploy a packaged component to an environment:**
1. `CREATE /PackagedComponent` referencing the `componentId` and version to snapshot.
2. `CREATE /Deployment` referencing the `packageId` and target `environmentId`.

**Update connection settings without republishing (Environment Extensions):**
1. `GET /EnvironmentExtensions/<environmentId>` to fetch the current extension values.
2. Modify only the needed connection/DPP/cross-reference values.
3. `UPDATE /EnvironmentExtensions/<environmentId>` with the full modified object — partial updates aren't supported, so always round-trip the full extensions payload.

## Guidance for scripts against this API

- Rate limits apply per account; batch reads with `QUERY`/bulk GET rather than looping single GETs where the object supports it.
- Treat the account's API token like any credential — never hardcode it; read from environment variables or a secrets store.
- `QUERY` results are paginated via `QueryMore` with a `queryToken` — a script that assumes a single page will silently truncate results on larger accounts.
- Distinguish `ExecutionRecord` (current/recent) from `ExecutionSummaryRecord` (broader historical/aggregate queries) — using the wrong one is a common cause of "missing" executions in a monitoring script.

When writing an automation script, confirm: target language, the account ID, which objects are involved, and whether auth will come from an API token or user credentials before generating code.
