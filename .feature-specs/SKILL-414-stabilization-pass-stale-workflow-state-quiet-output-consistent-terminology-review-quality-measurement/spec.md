# SKILL-414 - stabilization-pass-stale-workflow-state-quiet-output-consistent-terminology-review-quality-measurement

## Mode

decomposed

## Intended Outcome

A stabilization pass before further features (SKILL-413, the code-quality review lane, builds on it). Stale workflow-state rows no longer break listing, supersede or status. Normal CLI runs are quiet. One terminology is used across README, docs, the dispatcher and CLI help, and CLI routing rejects typos instead of treating them as goal intakes. Review quality becomes measurable through a small evaluation set that scores existing prose output on demand. Uninterpretable prose goes to curation without blocking reviews. No new user-facing features.

## Context

Over 8 months skill-bill grew from a ~15k-line markdown skill pack into a ~494k-line Kotlin workflow engine (origin/main 0f21eea96). The 2026-09-27/28 switch to phase slots, operations and a single /skill-bill dispatcher (SKILL-380 #410, SKILL-382 #411, SKILL-383 #412) changed the product shape again. In ~14 weeks there were 46 SQLite migrations and 9 feature-task contract bumps, and the docs and dispatcher drifted from the runtime.

### Area 1: Stale workflow-state rows must not break the runtime

- `WorkflowService.list` (runtime-application `workflow/service/WorkflowService.kt:379-395`) validates every row inside `rows.map`. The first invalid row throws and the whole list is lost. `WorkflowStateSchemaValidator` logs a WARNING and then throws. The schema requires `contract_version == "0.3"`.
- Workflow-state rows have no upgrade path. `ensureFeatureVerifyWorkflowColumns` only sets a column DEFAULT. Upserts keep the stored version (`WorkflowStateWrites.kt:203,227`) and re-validate it, so a stale row cannot even be abandoned. Feature-task rows have a readable-versions set (`FeatureTaskRuntimeContractVersions.kt`); workflow-state rows have none.
- Incident 2026-10-09: `VerifyOperation.supersedeParked` wraps the whole list in `skipUnreadable`. One contract_version 0.1 verify row from any repo silently cancelled the supersede pass, and older parked rows stayed pending. The supersede `store.write` result and the `failExtraction` write result are ignored.
- The same whole-list failure affects MCP `feature_verify_workflow_list`, work list (`WorkListService.kt:39-50`), `latest(VERIFY)` and IDE status (`IdeStatusProjector`).

### Area 2: Quiet, accurate CLI output

- No java.util.logging configuration exists, so the JDK default console handler prints INFO and above to stderr on every run.
- `GateJvmResolver.recordDecision` logs a routine success at WARNING on every agent launch and validation gate run.
- `JdkRuntimeDiagnostics` logs through a private `log()`, so every record shows source `JdkRuntimeDiagnostics log` instead of the caller.
- Best-effort warnings pass the Throwable, which prints full stack traces for handled conditions.

### Area 3: One terminology, accurate docs and help, safer routing

- "Phase" means three things: 5 standalone phases (`SkeletonDefinition.kt`), 11 `PhaseSlot`s (`PhaseSlot.kt`), and step ids. `verify` is an operation, not a phase. No glossary exists.
- README says nine phase slots (it omits `monitor` and `standalone_review`), says `phase:review` runs `code_review` (it runs `standalone_review`), and calls `write_history` a phase.
- The dispatcher `skills/skill-bill/content.md` contradicts itself on review mode vocabulary, review target syntax, which command drives a review, and tracker provider. It has a duplicate `Routing` heading and never names the sidecar it is injected with (`orchestration/skill-classes/feature-launch-warning.yaml`).
- CLI root help describes review import. `verify-stats` help says `bill-feature-verify`. `update-check` exists both top-level and as an operation.
- `routeIntake` turns any unrecognised first token into a goal intake, so a typo such as `skill-bill phse review` reaches goal intake and fails with a misleading intake message.
- `skill-bill phase verify` fails with `Unknown phase` and no hint.

### Area 4: Measure review quality

- No evals, golden-finding sets or calibration fixtures exist. Existing tests are structural.
- The required finding format `- [F-001] Sev | Conf | file:line | desc` (`orchestration/review-orchestrator/PLAYBOOK.md`) has no category. Table rows without a Category column default to `other`. Bullet findings fall back to the first routed specialist label for the whole review. In PostHog, ~1,048 of 1,183 triaged findings have no category.

## Decomposition

Four independent subtasks, one per area. Each touches a different part of the tree, ships on its own, and applies its rule to the tree as it finds it. There is no ordering between them.

| Subtask | Area | Why it is its own commit |
| --- | --- | --- |
| 1 | Stale workflow-state rows | Persistence, contracts and engine tolerance; reviewed by persistence and reliability. |
| 2 | Quiet CLI output | Process logging configuration and diagnostics adapter; unrelated to the rest. |
| 3 | Terminology, docs, help, routing | Docs and dispatcher text plus CLI help and routing; reviewed as user-facing wording. |
| 4 | Review quality measurement | An on-demand evaluation set and scorer for existing prose output. |

Shared files: subtasks 2 and 3 both edit `SkillBillCommand.kt`. Subtask 2 adds a `--verbose` root option and adds it to the `routeIntake` option-skip list. Subtask 3 changes the `routeIntake` goal guard and root help. Each subtask keeps every root option it finds in the skip list.

## Acceptance Criteria

1. Workflow-state rows written under a pre-current contract version can be read, listed and abandoned, or a migration terminalizes them. Subtask 1 records which path covers each version.
2. Workflow listing, latest, work list, IDE status and verify supersede skip or flag an unreadable row and keep every other row. The supersede and failExtraction write results are checked.
3. A default CLI run emits no java.util.logging output. `--verbose` or `SKILL_BILL_VERBOSE` re-enables it. Routine gate-JVM decisions are not WARNING. Diagnostics records carry the caller's class. Handled conditions log one line without a stack trace.
4. README carries a glossary of goal, workflow, subtask, phase, slot, step, operation, pack, lane and add-on. README, docs, the dispatcher and CLI help use those terms consistently, and the listed inaccuracies are fixed.
5. The dispatcher has one rule per decision. CLI routing sends only issue-key, URL or spec-path intakes to goal. `phase verify` and `phase:verify` point to `operation verify`.
6. Reviews remain prose, with no new finding template, schema, production parser change, or review validation. An on-demand evaluation set reports precision and recall per lane where existing output can be interpreted, and lists the rest for curation with explicitly partial scores. Its first case is capmo-android PR #3110.

## Non-Goals

- No new user-facing features. SKILL-413 (code-quality review lane) is separate and follows this.
- No change to review finding rules or lane scopes.
- No required structured review output, strict schema parsing, production parser changes, new review validation, or new review blocking conditions. Evaluation scores do not gate completion or validation.
- No telemetry privacy change: `anonymous` stays the default level.
- No bump of `WORKFLOW_STATE_CONTRACT_VERSION`.

## Constraints

- Supplied requirements are authoritative and need no tracker lookup.
- Mocks use `relaxUnitFun = true`, never `relaxed = true`.
- Tests that build a runtime environment map pass an explicit non-empty map, because an empty map falls back to the host environment.

## Validation Strategy

The validate phase runs the repository's full check (`./gradlew check`, which covers spotless, detekt, architecture repo tests, render snapshots and agent-config validation) once per subtask commit. If spotless reports a stale configuration cache, rerun it with `--no-configuration-cache`. Audit checks each acceptance criterion against the tree.
