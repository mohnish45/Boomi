---
description: Help design agents, tools, and knowledge bases in Boomi Agentstudio, and wire agents into Boomi integration processes. Use when the user mentions Agentstudio, building an AI agent on the Boomi platform, or connecting an agent to a Boomi process/connector.
---

# Boomi Agentstudio

Boomi Agentstudio is Boomi's platform for building AI agents that can call tools (including Boomi integration processes) and ground responses in a knowledge base, then expose the agent via API or embed it in a process.

## Core concepts

- **Agent**: defined by a system prompt/instructions, a model choice, and the set of tools and knowledge sources it's allowed to use.
- **Tools**: an agent's tools are typically existing Boomi processes (or specific connector operations) exposed with a name/description/parameter schema — the same discipline as writing any tool description for an LLM: be explicit about what the tool does, its inputs, and when to use it, since the agent's model only sees the description, not the process internals.
- **Knowledge base**: a document/data source (uploaded docs, a connector-fed source) the agent retrieves from to ground answers; keep it scoped to what the agent actually needs to answer correctly rather than dumping unrelated documentation into it, which increases retrieval noise.
- **Deployment**: an agent is invoked either directly via its own API endpoint, or from inside a Boomi integration process (e.g. an Agent shape/connector calling the agent mid-process to make a decision or draft content).

## Design guidance

- Give each tool a narrow, well-described scope (one process = one clear capability) rather than one large multi-purpose process — agents pick tools by description, and an overloaded description leads to wrong tool selection.
- Validate/guard tool inputs inside the underlying Boomi process itself (don't rely on the agent to always pass well-formed parameters) since the process can be called directly outside the agent's guardrails too.
- For agents embedded in a process (not just standalone chat), decide explicitly what happens on an agent error or low-confidence response — route to a human/manual-review path rather than letting an uncertain agent output flow downstream unchecked.
- Keep the knowledge base current with an integration process that re-syncs source documents on a schedule, rather than a one-time manual upload, if the underlying source data changes.

Ask which existing Boomi processes should become tools, and what the agent's decision boundary is (fully autonomous vs. human-in-the-loop) before proposing an agent design.
