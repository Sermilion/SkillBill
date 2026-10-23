---
name: shell-ceremony
description: Shared ceremony sidecar for governed shells and horizontal skills.
---

# Shared Ceremony

This file is the canonical ceremony sidecar for governed platform-pack skills
and for horizontal shells that point their project-overrides section here.
Installed skills consume this file through generated sibling support pointers,
so changes here propagate to every linked skill after render/install refresh.

Do not reference this repo-relative path directly from installable skills — use
the generated sibling `shell-ceremony.md` support pointer instead.

## Project Overrides

This is an orchestrator-owned ceremony step. The current skill's main agent
reads `.agents/skill-overrides.md` directly. Do not delegate this read to a
subagent — mandates that target the orchestrator's own lifecycle (e.g. tools
to call before applying the skill body, writes to make at the end of the
skill body) cannot be fulfilled by a subagent that runs at a different
lifecycle position.

If `.agents/skill-overrides.md` exists in the project root and contains a
section whose H2 heading matches this skill's `name` from the frontmatter,
read every bullet under that heading verbatim and treat the section as the
highest-priority instruction for this skill. The matching section may refine
or replace parts of the default workflow.

### Action mandate detection

Inside the matched section, any bullet that names a concrete action is an
**action mandate** that the orchestrator MUST execute at the lifecycle
position the bullet specifies. Detect mandates by simple substring match:

- A token starting with `mcp__` names a tool the orchestrator MUST call.
- A phrase like `read \`{path}\`` or "read the file `{path}`" names a file
  the orchestrator MUST read.
- A phrase mentioning `write_state`, `write_episode`, or "write to `{path}`"
  names a write the orchestrator MUST perform.

### Lifecycle positions

Action mandates land at one of two ceremony positions:

- **Before applying the skill body** (preflight): bullets containing phrases
  such as "before applying the skill body", "at session start", or naming a
  `*_session_start`-style tool. Execute these BEFORE the skill's first
  authored step runs.
- **At end of skill** (postflight): bullets containing phrases such as "at
  the end of the skill work", "before finishing", or naming `write_state` /
  `write_episode`. Execute these AFTER the skill's last authored step but
  BEFORE the skill emits its terminal `*_finished` telemetry event.

### Recording the result

Record both passes in a structured form so audit and review can verify the
mandates fired. Recommended shape:

```
{
  "file_present": <bool>,
  "section_found": <bool>,
  "section_heading": "<heading or empty>",
  "actions_executed": [
    {"tool": "<name>", "status": "ok|error", "summary": "<short>"}
  ]
}
```

Skills with structured workflow state (e.g. the feature workflow)
persist preflight under the assess-phase artifact and postflight under the
finalization-phase artifact. Skills without structured state may report the
result inline in their final summary.

### Subagent boundary

When a skill's pre-planning or briefing step delegates reading
`.agents/skill-overrides.md` to a subagent, the subagent's job is to
**surface** the matched section verbatim and surface action mandates as
structured fields — never to execute them. Execution is the orchestrator's
responsibility regardless of which subagent surfaced the mandate.

### Skipping when no override applies

If `.agents/skill-overrides.md` is missing, or the file exists but has no
section matching this skill's name, record `file_present` / `section_found`
as `false` and proceed without action.

### Other project guidance

If an `AGENTS.md` file exists in the project root, apply it as project-wide
guidance.

Precedence: matching `.agents/skill-overrides.md` section > `AGENTS.md` >
built-in defaults.

## Built-in Coding Standards

These are framework defaults that implementation skills follow when writing code
and review skills enforce when reading it. They sit at the lowest tier of the
precedence above: a matching `.agents/skill-overrides.md` section or an
`AGENTS.md` rule may relax or replace any of them.

### Comments

Authored Kotlin under `runtime-kotlin` and
`runtime-kotlin/build-logic` must contain no `//` line comments and no non-KDoc
`/* */` block comments. `/** */` KDoc is allowed only on `interface` declarations
and their members, including nested types inside an interface. Prefer clear names and small functions; record irreducible
why-only rationale in the owning area `agent/decisions.md`. This matches
`AGENTS.md` and `CommentAndInterfaceKdocArchitectureTest`.

## Inputs

Read the current skill's `SKILL.md` frontmatter before execution so project
overrides resolve against the correct skill name. The rendered `## Execution`
body in `SKILL.md` is the authored guidance for the current skill.

## Execution Mode Reporting

When the current skill runs, report the execution mode on its own line:

```
Execution mode: inline | delegated
```

- `inline` — the current agent handled the work directly.
- `delegated` — the current agent dispatched the work to a specialist subagent
  or sibling skill.

## Telemetry Ceremony Hooks

Follow the standalone-first telemetry contract documented in the sibling
`telemetry-contract.md` file:

- Emit a single `*_started` event at the top of the ceremony.
- Emit a single `*_finished` event at the bottom of the ceremony.
- Routers aggregate `child_steps` but never emit their own `*_started` or
  `*_finished` events.
- Degrade gracefully when telemetry is disabled: the skill must still run to
  completion without an MCP connection.
