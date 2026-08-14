---
description: Help find, evaluate, or adapt a prebuilt Boomi Marketplace recipe or connector before building an integration from scratch. Use when the user is starting a new integration and wants to know if a template/connector already exists for it (e.g. "Salesforce to NetSuite order sync").
---

# Boomi Marketplace recipes

The Boomi Marketplace hosts prebuilt integration recipes, connectors, and process templates contributed by Boomi and partners, browsable from within AtomSphere (Build tab) or at the Boomi Marketplace site.

## When to check the Marketplace first

Before scaffolding a new process from scratch, check whether a recipe already covers the integration pattern the user described — common categories: CRM-to-ERP order/customer sync, e-commerce order fulfillment, EDI trading-partner onboarding kits, HR system-of-record sync, and connector packs for specific SaaS apps not covered by Boomi's standard connector library.

## Evaluating a candidate recipe

When the user has found or is considering a recipe, help them check:
- **Connector coverage**: does it use connectors/versions already licensed and available in the account, or does adopting it require enabling a new connector?
- **Profile/version match**: recipes are often built against a specific API version of the source/target system (e.g. a NetSuite SuiteTalk version) — confirm it matches the target system's current version before assuming it will work unmodified.
- **Customization points**: identify which maps, decision branches, and DPPs are meant to be edited per-account (most recipes ship with placeholder credentials/environment extensions, not business logic that should be treated as fixed).
- **Scope gaps**: recipes usually cover the happy path; explicitly check what error handling, retry, and edge-case logic (partial batches, duplicate detection) is missing and would need to be added for production use.

## Adapting a recipe

Treat an imported recipe like any other imported component: review its Environment Extensions requirements, re-point connections at the target account's credentials, and run it in Test before deploying to Production — don't assume a Marketplace recipe is production-ready as-is just because it's official.
