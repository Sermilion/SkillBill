---
name: workflow-contract
description: Versioned contract for top-level orchestrator workflows that need durable step state, artifact handoff, and workflow-owned telemetry while reusing standalone skills as leaf steps.
---

# Shared Workflow Contract

This is the canonical workflow contract for Skill Bill. It defines when a
top-level skill should be treated as a workflow, what state a workflow owns,
how step handoff artifacts stay explicit, and how child skill telemetry rolls
up into a single parent-owned lifecycle.

This contract complements existing skill contracts; it does not replace them.
Skills remain the user-facing entry points and the reusable execution units.
Workflows add durable orchestration for the small set of top-level commands
whose behavior already spans multiple sequential child steps.

## Contract Version

The current workflow contract version is **`0.1`**.

- `0.x` means the shape is piloted and may still tighten before a stable `1.0`.
- The first pilot scope is the feature-task runtime.
- The feature-verify workflow (`skill-bill operation verify`) is the next intended adopter.

## When To Use A Workflow

Use the workflow contract only when all of the following are true:

- the command is a stable top-level entry point
- it coordinates multiple child skills or subagents over several phases
- the phases exchange structured artifacts that matter after the child exits
- resume, retry, or auditability would be materially improved by durable step
  state rather than prompt-only memory

Do not use the workflow contract for:

- routed leaf skills such as `bill-kotlin-code-review`
- governed add-ons
- single-pass shells that do not coordinate multi-step state
- specialist review passes whose output is already owned by a parent review

## Relationship To Skills

Workflows and skills have different responsibilities:

- **skills** own user-facing behavior, routing, rubrics, and standalone use
- **workflows** own step state, artifact handoff, retries, resume points, and
  parent-level completion semantics

A workflow must invoke existing skills wherever a skill boundary already
exists. The workflow layer must not duplicate stack routing, review rubrics,
quality-check behavior, or add-on selection logic that already belongs to a
skill.

## Workflow-Owned State

Every workflow run owns a single state object. A runtime may persist it in any
backend, but the shape below is the contract:

```json
{
  "workflow_id": "wfl-20260421-142530-a1b2",
  "workflow_name": "feature-task-runtime",
  "contract_version": "0.1",
  "status": "running",
  "current_step_id": "plan",
  "steps": [
    {
      "step_id": "assess",
      "status": "completed",
      "attempt_count": 1
    }
  ],
  "artifacts": {},
  "child_steps": []
}
```

Required top-level fields:

- `workflow_id` — unique id for the workflow run
- `workflow_name` — stable top-level command name, e.g. `feature-task-runtime`
- `contract_version` — workflow contract version string
- `status` — `pending | running | completed | failed | abandoned`
- `current_step_id` — stable step id currently being evaluated, or empty when
  terminal
- `steps` — ordered list of step state objects
- `artifacts` — map of named workflow artifacts
- `child_steps` — collected telemetry payloads returned by child skills invoked
  with `orchestrated=true`

Required per-step fields:

- `step_id` — stable identifier, never user-visible prose
- `status` — `pending | running | completed | failed | blocked | skipped`
- `attempt_count` — integer incremented on each re-run of the same step

Optional per-step fields:

- `started_at`
- `finished_at`
- `failure_reason`
- `depends_on`
- `artifact_keys`

## Artifact Handoff Contract

Every boundary between workflow steps must be explicit about what artifact the
parent carries forward. A step may emit prose for a human, but the workflow
must retain a structured artifact for later steps.

Artifact rules:

- Artifacts use stable keys such as `assessment`, `plan`, or `audit_report`.
- The parent workflow is the authority for artifact storage.
- Child steps may read only the artifacts the parent passes into their
  briefing or invocation.
- A workflow step must not require re-reading a raw upstream spec when an
  authoritative parent-owned artifact already exists for that phase.
- Human-readable summaries are optional; artifact keys and meanings are not.

## Step Semantics

Each workflow step must declare:

- a stable `step_id`
- its inputs
- its success artifact or side effect
- its retry rule
- its failure boundary
- whether it is safe to resume from the next step without rerunning it

Shared step rules:

- Steps are sequential by default.
- Parallel fan-out is allowed only when child steps have disjoint write scope
  or are read-only and the parent owns fan-in.
- A failed step leaves the workflow in `failed` or `blocked`; the parent must
  not silently continue.
- Retrying a step increments `attempt_count` and replaces or supersedes that
  step's prior artifact.
- A workflow may skip a step only when the governing skill contract already
  defines that skip as legal.

## Parent-Owned Telemetry

The workflow contract extends the existing standalone-first telemetry contract
in `orchestration/telemetry-contract/PLAYBOOK.md`.

- The top-level workflow owns the lifecycle event.
- Child skills invoked inside the workflow must use `orchestrated=true`.
- Returned child `telemetry_payload` objects are appended to `child_steps`.
- The workflow emits one parent finished event with the collected `child_steps`.

This preserves the current Skill Bill rule: one user-initiated workflow should
produce one authoritative completion event.

## Runtime Pilot Surface

The runtime-facing pilot uses dedicated MCP tools per top-level workflow.
The feature-verify workflow is the remaining MCP-facing adopter; feature-task and
goal execution drive their workflow state from the Kotlin runtime driver and
the CLI instead:

- `feature_verify_workflow_list`
- `feature_verify_workflow_latest`
- `feature_verify_workflow_open`
- `feature_verify_workflow_update`
- `feature_verify_workflow_get`
- `feature_verify_workflow_resume`
- `feature_verify_workflow_continue`

These tools persist workflow state independently of telemetry settings. The
existing `feature_verify_started` / `_finished` tools remain telemetry-owned;
they are linked to workflow state via `session_id` rather than replaced by it.

Workflow update tools return compact acknowledgements by default after the
validated update has been persisted. The acknowledgement includes write status,
workflow id/status, current step id, updated step ids, updated artifact keys,
database path, and read-only full-state guidance. It intentionally omits full
steps and durable artifacts; callers that need complete state should use the
read-only `*_workflow_get` MCP tools or CLI `verify-workflow show`, and for
feature-task / goal runs the corresponding `feature-task status` / `goal status`
surfaces.

`skill-bill feature-task` and `skill-bill goal` are the activation surfaces for
feature-task and goal runs: they own durable state, continuation lookup, and
foreground execution in-process. Verify workflows still expose a compact
continuation payload via `skill-bill verify-workflow continue`, including the
resumed step id, required and available artifact keys, compact current-step
artifact summaries, reference sections to read, and a paste-ready continuation
prompt. The compact payload omits the full workflow snapshot and full durable
`artifacts` map by default. Use `verify-workflow show` as the read-only
full-state inspection path when complete durable verify state is needed.

The CLI exposes the recovery and activation surface through:

- `skill-bill feature-task status|lookup|…`
- `skill-bill goal status|watch|…`
- `skill-bill verify-workflow show <workflow-id>`
- `skill-bill verify-workflow resume <workflow-id>`
- `skill-bill verify-workflow continue <workflow-id>`

For decomposed feature parents, `skill-bill goal` is the goal-continuation
entry: it starts or resumes only the selected runnable subtask, derives the
subtask contract from persisted artifacts and the subtask spec, suppresses PR
creation when continuing a child, and records the machine-readable outcome in
durable workflow state. A later subtask blocks until dependencies are complete.
Outcome fields are `issue_key`, `subtask_id`, terminal `status`, `commit_sha`,
`workflow_id`, `blocked_reason`, and `last_resumable_step`. Runtime state is the
authoritative channel; stdout and git-tracked manifest projections are
diagnostic/recovery views.

### `verify-workflow continue` vs `verify-workflow show`: mutating activation vs read-only inspection

These two surfaces have different contracts and must not be confused:

- **`verify-workflow continue` is mutating activation.** It re-opens resumable
  state (transitioning a blocked/idle workflow back to active) and returns the
  governed **compact** continuation payload — resumed step id, required and
  available artifact keys, compact current-step artifact summaries, reference
  sections, and a paste-ready continuation prompt. It is the path a session uses
  to *resume verify work*. The compact payload deliberately omits the full
  workflow snapshot and the full durable `artifacts` map.
- **`verify-workflow show` is read-only inspection.** It mutates nothing and
  returns the full snapshot (every step plus the complete durable `artifacts`
  map). Use it for debugging and full-state recovery, never as the normal resume
  path.

**Goal child sessions should use the compact continuation output.** A
goal-continuation child resumes from the compact continuation payload and
treats `current_step_artifacts` as authoritative current-step context instead of
reconstructing prior context from chat history. **Fetch full state only when
explicitly needed** — e.g. an omitted/large artifact must be inspected — via the
read-only `goal status` / `feature-task status` / `verify-workflow show` path.
Compact continuation and the compact update acknowledgement carry documented
byte budgets (covered by size-assertion regressions) so a full snapshot cannot
silently leak back into the default child-session payload.

### Inspecting the attempt ledger (why a subtask retried, stopped, or blocked)

The goal runner persists an append-only attempt/event ledger as the durable
`goal_attempt_ledger` artifact on the relevant workflow record. Read it with
`skill-bill goal status` (or the durable artifacts map on the child workflow
record) to answer *why* a subtask behaved the way it did — **without scraping
any provider JSONL session log.** Each entry carries an `action` plus explanatory
fields:

- `child_activation` — first start of a subtask child run.
- `resume` — a previously blocked subtask was resumed.
- `retry` — a child returned no terminal store outcome and was retried inline
  (`stop_reason=no_terminal_store_outcome`).
- `terminal_done_check` — the runner confirmed a subtask reached a terminal done
  state (`final_reconciled_result` explains the result).
- `policy_block` — the run was blocked by policy before launch
  (`blocked_reason` + `stop_reason=policy_blocked`).
- `timeout` — the child was killed as unresponsive (`stop_reason=timeout`).
- `interruption` — the child was killed by a parent interrupt
  (`stop_reason=interrupted`).
- `final_reconciled_outcome` — the reconciled result recorded at goal
  finalization (`final_reconciled_result`).

The explanatory fields `blocked_reason`, `stop_reason`, and
`final_reconciled_result` are sufficient on their own to explain a retry, stop,
or block; the ledger sequence space is distinct from the `goal_event` and
`goal_progress` spaces.

> **CAVEAT (not a Skill Bill contract): cached-input token totals.**
> Provider-reported *total* token counts for a child session can be dominated by
> cached input replay — re-reading the same large context on each turn inflates
> the reported input-token total without reflecting new work. Skill Bill
> therefore optimizes **payload size and session behavior** (compact
> continuation, transition-only monitoring, read-only-on-demand full state)
> rather than relying on provider cache accounting. Best-effort session
> accounting (`goal_session_accounting`) is recorded when provider data exists
> and reported as unavailable — without failing the run — when it does not. Treat
> provider cached-token totals as a diagnostic signal, not a Skill Bill
> guarantee or billing input.

## Pilot: feature-task runtime

The feature-task runtime is the first workflow contract pilot. Its stable step
ids are:

1. `assess`
2. `create_branch`
3. `preplan`
4. `plan`
5. `implement`
6. `review`
7. `audit`
8. `validate`
9. `write_history`
10. `commit_push`
11. `pr_description`
12. `finish`

For this pilot, the authored workflow entry source stays in
`skills/skill-bill/content.md`. Install and render flows generate the
runtime `SKILL.md` wrapper from that source plus the shared shell contract.
Generated wrappers are not committed under `skills/`.
The rendered `SKILL.md` remains the runtime-facing source of truth for:

- workflow-state and continuation sections
- stable step headings used by continuation payloads
- stable artifact names
- telemetry ownership and final lifecycle fields

Required workflow artifacts for the pilot:

- `assessment` — accepted criteria, non-goals, open questions, feature size,
  feature name, rollout need
- `preplan_digest` — boundary history/decision findings, reusable patterns,
  selected add-ons, validation strategy
- `plan` — ordered tasks or phases with criteria coverage
- `implementation_summary` — files changed, tasks completed, deviations, tests
  written
- `review_result` — prioritized findings plus diff pointer used for review
- `audit_report` — per-criterion pass/fail evidence
- `validation_result` — routed skill or repo-native validator result
- `history_result` — written/skipped outcome for boundary history
- `commit_push_result` — reserved shell-owned artifact for Step 10. The runtime stages every dirty
  non-ignored path into the owned subtask commit using a subject from the issue key and subtask
  name. Feature-spec files are included only when they are not gitignored. It then captures the sha,
  pushes, and prunes checkpoint refs after the manifest records `commit_sha`. If nothing remains to
  commit, it finishes and pushes the current HEAD. This phase does not launch an agent.
- `pr_result` — PR url/title or terminal failure note
- `monitor` (no named artifact) — settles the CI verdict for the PR the run
  created with a prose value. The runtime watches the PR checks and settles as
  passed, no-CI-configured, or failing. Failed checks route to
  `monitor_fix` on that poll, even while other checks are pending. Passing
  requires every check to pass, be skipped, or be cancelled with none pending.
  Monitor is runtime-owned: it does not launch an agent and does not read CI
  state from agent output.

Pilot-specific retry rules:

- `review` may loop back to `implement` up to the governing skill's max review
  iterations
- `audit` may loop back to `plan` and `implement` up to the governing skill's
  max audit iterations
- `validate` retries in place until pass/fail is final
- `commit_push` and `pr_description` are terminal gates; no downstream step may
  continue if they fail. `commit_push` is runtime-owned: it does not launch an
  agent, and it does not run `git commit` or `git push` from agent output.
- `monitor` runs after `pr`. On failing CI it routes to `monitor_fix`, which
  fixes the failures and returns to `commit_push`, then `monitor` re-checks the
  PR. At most 3 `monitor_fix` attempts run per subtask. When CI still fails
  after the third attempt, the run blocks with the last failing checks and the
  last fix summary as the reason.

## Authoring Boundary

The workflow contract should stay in orchestration, not in platform packs.

- Platform packs may enrich child skills used by a workflow.
- Platform packs do not define new top-level workflow state machines.
- The top-level workflow remains manifest-agnostic unless and until Skill Bill
  introduces a governed workflow manifest shape.

## Non-Goals

- Not a replacement for `SKILL.md`, `content.md`, or routed skill manifests
- Not a generic DAG engine for arbitrary repo automation
- Not a reason to create a new slash command per workflow step
- Not a hidden fallback layer that continues after a failed step
- Not a second telemetry system separate from the existing parent-owned model

## Next Adoption Rules

Before another command adopts the workflow contract, confirm all of the
following:

- the command already behaves like a multi-step orchestrator in practice
- durable step state would improve resume, retry, or audit behavior
- the child boundaries already have stable artifact contracts or can be given
  them cleanly
- the workflow would reduce prompt duplication instead of creating a second
  source of truth
