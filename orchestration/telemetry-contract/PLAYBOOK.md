---
name: telemetry-contract
description: Single source of truth for shared telemetry event semantics, orchestration flags, and learnings resolution. Installed skills link to this via generated support pointers.
---

# Shared Telemetry Contract

## Delegated lifecycle evidence

Review telemetry consumes bounded mode accounting. It keeps the resolved mode, the routed areas
selected and completed, launched and failed lanes, and terminal state as separate fields. It never
persists prompts, complete diffs, raw transcripts, or tool logs.

`delegated` is the experimental full-depth review — specialist subagent fan-out inside the invoking agent's harness, reached only by explicit selection.
`inline` is the reduced-depth review through one
inline review session. `auto` resolves to
`inline` everywhere — a first pass, any scope with no pass number, and every follow-up or remediation
pass — as does an omitted mode.
Telemetry reports which mode produced the result, including the applicable named `auto` rule, and
records that specialist depth was not applied for an inline result; it never rewrites the resolved
mode or substitutes one mode for another.

This is the canonical telemetry contract for skill-bill skills. Installed skills consume it through a generated sibling `telemetry-contract.md` support pointer inside each staged skill directory, so changes here propagate to every linked skill after render/install refresh.

Do not reference this repo-relative path directly from installable skills — use the generated sibling support pointer instead.

## Standalone-first contract

Every telemeterable skill must be usable alone. When invoked directly by a user, each skill generates its own session id and emits its own events:

- `skill-bill phase review` — `skillbill_review_finished` (once the review lifecycle resolves)
- `skill-bill phase validation` — `skillbill_quality_check_started` + `_finished`
- `skill-bill operation verify` — `skillbill_feature_verify_started` + `_finished`
- `skill-bill phase pr` — `skillbill_pr_description_generated`

## The `orchestrated` flag

Every telemeterable MCP tool accepts `orchestrated: bool = false`.

- **`orchestrated=false` (standalone):** the tool generates its own session id, emits started/finished events, and owns its lifecycle.
- **`orchestrated=true` (nested):** the tool emits **zero** telemetry events. Instead it returns a `telemetry_payload` dict on the tool result that the caller (the orchestrator) collects.

The orchestrator is responsible for setting the flag. A child skill never infers orchestrated mode from ambient state.

## MCP tools by skill kind

### Review skills

Review skills (`skill-bill phase review` and its stack-specific implementations) use the `import_review` and `triage_findings` MCP tools.

- **Standalone:** `import_review` imports the review text and emits telemetry; `triage_findings` records user feedback and completes the review lifecycle.
- **Orchestrated (`orchestrated=true`):** both tools suppress outbox emission and return a `telemetry_payload` for the parent to embed.

### Quality-check skills

Quality-check skills (`skill-bill phase validation` and its stack-specific implementations) use the `quality_check_started` and `quality_check_finished` MCP tools.

- **Standalone:** call `quality_check_started` once stack routing is decided, then `quality_check_finished` when the loop finishes.
- **Orchestrated:** skip `quality_check_started`; call `quality_check_finished` with `orchestrated=true` and all started+finished fields combined.

### Feature-verify skills

Feature-verify skills (`skill-bill operation verify`) use the `feature_verify_started` and `feature_verify_finished` MCP tools.

- **Standalone:** call `feature_verify_started` after criteria are confirmed, then `feature_verify_finished` after the verdict.
- **Orchestrated:** skip `feature_verify_started`; call `feature_verify_finished` with `orchestrated=true` and all started+finished fields combined.

### PR description skills

PR description skills (`skill-bill phase pr`) use the `pr_description_generated` MCP tool.

- **Standalone:** call `pr_description_generated` after the PR description is presented.
- **Orchestrated:** call `pr_description_generated` with `orchestrated=true`.
- For orchestrated PR creation, compare the normalized generated body with the body passed to
  `gh pr create` or read back from `gh pr view --json body`; set `was_edited_by_user=true` when
  they differ.

### Runtime blocked fields

Blocked telemetry reasons use a category prefix so analysis can bucket outcomes. Valid prefixes are
`limit:`, `validation:`, `fix_loop:`, `git:`, `store_lock:`, `needs_human:`, `schema:`, `runtime:`,
and `unknown:`.

`skillbill_feature_task_runtime_finished.last_incomplete_phase` is non-empty for blocked and error outcomes.
Completed outcomes emit `completed`; planning decompositions emit `decomposed_at_planning`.

`goal_started.status` is emitted as `running`.

### Least-context projection measurements

Feature-task-runtime projection accounting is emitted only through the telemetry
and progress stores. It never becomes a phase receipt, review result, validation
receipt, continuation artifact, or other domain input.

`skillbill_feature_task_runtime_projection_measurement` carries only workflow,
consumer phase, projection-contract, producer-iteration, and repository-checkpoint
identifiers plus projected UTF-8 bytes, collection-item counts, estimated tokens,
private-evidence bytes, delivered-projection bytes, and an optional closed failure
classification. Accepted projections record their measured sizes. Rejected
projections record zero delivered sizes and classify contract, version, budget,
unprojectable-source, stale-checkpoint, stale-producer, or sibling-context failure.

The event must never contain prompt text, source or diff bodies, receipt bodies,
raw phase outputs or payloads, transcripts, tool output, command logs, excerpts,
or arbitrary metadata. Measurements are content-free accounting; private evidence
remains available only through the diagnostic persistence boundary.

### Feature-task skills

Feature-task and goal execution emit their lifecycle telemetry from the Kotlin runtime driver, not from MCP tools. These emissions are always top-level — they are not invoked with `orchestrated=true` by other skills.

## child_steps aggregation

When the parent's finished event fires, it embeds each collected `telemetry_payload` in a `child_steps` array. One workflow, one event. Orchestrator skills must:

1. Pass `orchestrated=true` to every child MCP tool they invoke.
2. Collect the `telemetry_payload` dict returned by each child tool.
3. Append each payload to a running `child_steps` list.
4. Pass the complete `child_steps` list to their own finished tool call.

## Routers never emit

`skill-bill phase review` and `skill-bill phase validation` are thin routers. They do not emit telemetry of their own — routing metadata is carried inside the concrete routed skill's telemetry call. They pass `orchestrated` through to the routed concrete skill unchanged.

## Governed add-ons

Governed add-ons are stack-owned guidance assets, not standalone skills.

- Add-ons never emit telemetry, own sessions, or appear in `child_steps`.
- Resolve add-ons only after stack routing selects the owning package.
- If a routed skill reports add-ons, include them as ordinary routing metadata such as `Selected add-ons: ...`; do not treat add-ons as separate routed skills.

## Telemetry Ownership

The review layer that owns the final merged review output for the current review lifecycle owns review telemetry.

- If this review is delegated or layered under another review, do not call `import_review`. Return the complete review output plus summary metadata (`review_session_id`, `review_run_id`, detected scope/stack, execution mode, specialist reviews) to the parent review instead.
- If this review owns the final merged review output for the current review lifecycle, call the `import_review` MCP tool:
  - `review_text`: the complete review output (Section 1 through Section 4)

Review finished payloads, whether emitted standalone or returned through `telemetry_payload`, include terminal latest-outcome counts and rates: `total_findings`, `accepted_findings`, `rejected_findings`, `unresolved_findings`, `accepted_rate`, and `rejected_rate`. They also include the original `review_platform` / `review_scope` labels plus normalized `platform_slug` and `scope_type` dimensions. Accepted and rejected finding details include `issue_category`, `severity`, `confidence`, and `outcome_type`; anonymous telemetry keeps those fields but redacts file locations, descriptions, rejection notes, and learning text.

## Triage Ownership

The same parent review owns triage recording after the user responds to findings so the review lifecycle can complete and the finished telemetry event can fire.

- If this review is delegated or layered under another review, do not call `triage_findings`; the parent review owns triage handoff and telemetry completion.

Each finding gets one decision using its position number from the risk register:
- `fix` — the finding was accepted and the fix was applied
- `accept` — the finding was accepted but no code change was needed
- `skip` — the finding was intentionally skipped (append a reason after ` - `)
- `false_positive` — the finding was incorrect

- If this review owns the final merged review output for the current review lifecycle and the user responds to findings, call the `triage_findings` MCP tool:
  - `review_run_id`: the review run ID from the review output
  - `decisions`: prefer a single structured selection string that fully resolves the review, e.g. `["fix=[1,3] reject=[2]"]`
  - fallback: explicit numbered decisions still work, e.g. `["1 fix", "2 skip - intentional", "3 accept"]`

Skip triage recording when the final parent-owned review produced no findings.

### Learning candidates

`triage_findings` returns `learning_candidates` — one entry for each recorded `false_positive` or `fix_rejected` decision that carried a note. Each candidate has `suggested_title`, `suggested_rule_text`, `suggested_scope`, and `suggested_scope_key`. Triage itself never creates a learning.

- If this review owns triage, show every candidate to the user once, as a single batch listing title, suggested rule, and suggested scope with its scope key. Ask which to promote and with which scope.
- Call the `add_learning` MCP tool only for the candidates the user confirmed, passing `scope`, `scope_key`, `title`, `rule_text`, `source_review_run_id`, and `source_finding_id`. Never promote a candidate without confirmation.
- If this review is delegated or layered under another review, neither offer candidates nor call `add_learning`.

## Graceful degradation

If a parent skill forgets to pass `orchestrated=true` to a child, the child emits its own standalone event. The workflow produces extra events but nothing is lost. Always pass the flag from the orchestrator's skill instructions.
