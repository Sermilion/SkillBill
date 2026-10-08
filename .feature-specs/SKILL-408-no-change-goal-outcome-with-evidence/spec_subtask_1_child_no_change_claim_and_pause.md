# SKILL-408 Subtask 1: No-change claim, evidence audit and operator pause

Parent: `.feature-specs/SKILL-408-no-change-goal-outcome-with-evidence/spec.md`. Use the parent's
Shared Contract names exactly.

## Scope

Implementation can claim no change with evidence. The audit checks that evidence instead of the
diff. A confirmed claim stops the run with a persisted `no_change_pause` artifact and the goal
stop reason `awaiting_no_change_decision`, and it shows a report. A missing or rejected claim
goes through the normal repair loop and carries its reason with it. This subtask does not cover
operator decisions on the pause, which belong to subtask 2.

Path prefixes: DOM = `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/`,
ENG = `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/`,
CON = `orchestration/contracts/`.

## Owned Paths

- DOM: a new claim model and structural validator, for example under
  `workflow/taskruntime/model/audit/` next to `FeatureTaskRuntimeAuditGapPersistenceModels.kt`.
- DOM: a new `FeatureTaskRuntimeNoChangePause` artifact model, modeled on
  `FeatureTaskRuntimeAuditGapPause`.
- DOM `workflow/model/validation/FeatureTaskRuntimeVerdict.kt` (uncommitted SKILL-407 edits are
  already in it). This is the assumed home of the verdict words. Confirm before editing.
- DOM `goalrunner/model/GoalRunnerTerminalModels.kt` (the stop reason and its three exhaustive
  `when` blocks).
- DOM `workflow/taskruntime/phase/task/FeatureTaskRuntimePhaseWorkflowTransitions.kt` and
  `FeatureTaskRuntimePhaseWorkflowGraph.kt` (both already have uncommitted edits). Used only if
  skipping simplify needs a transition change.
- ENG `featuretask/slot/audit/AcceptanceAuditRound.kt`, `AcceptanceAuditVerdictRule.kt` and
  `AcceptanceAuditStrategy.kt`. Also ENG `featuretask/runloop/attempt/FeatureTaskRuntimeRunLoopAuditSettlement.kt`
  and ENG `featuretask/runloop/output/FeatureTaskRuntimeRunLoopOutputVerification.kt`.
- ENG `goalrunner/status/GoalRunnerStopReports.kt` and
  `goalrunner/telemetry/GoalRunnerTelemetryEmitter.kt`.
- `runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/audit/opus-5-5-acceptance-audit.md`,
  plus the implement-phase prompt or directive resource, wherever it lives.
- CON `feature-task-runtime-phase-output-schema.yaml` and `telemetry-event-schema.yaml` (both
  already have uncommitted edits). CON `workflow-state-schema.yaml` only if child artifacts list
  their record kinds there.
- The CLI goal-run output path that prints the stop line, matching the format of the
  `awaiting_confirmation` line at `OperationCommand.kt:34/181`.
- Tests under the matching `src/test` trees, including
  `runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/featuretask/slot/FeatureTaskRuntimePhaseOutputFixtures.kt`
  and the audit tests in `featuretask/runner/`.

## Implementation Steps

1. **Claim model and validator.** Add a pure domain model for `produced_outputs.no_change` and a
   validator that returns either a valid claim or a rejection reason listing every missing or
   invalid part. The checks are:
   - the reason is unknown or missing;
   - a catalog criterion id has no entry, or its verdict or evidence is blank;
   - no citation matches `path:line` or `path:start-end`;
   - `boundary_trace` is blank;
   - the step changed one or more files.

   When `suggested_handoff` is absent, derive it:
   - `out_of_repo`: "Hand off to <owning_system or 'the owning system (not identified)'>".
   - `already_satisfied`: "Close the issue as already satisfied".
   - `not_reproducible`: "Return the issue to the reporter for reproduction steps".

   This step exists so that missing evidence is reported deterministically instead of falling
   through to the generic remaining-criteria parser. (AC 1, 2)
2. **Implement completion with no change.** Find where the mutating phase output is checked
   (`FeatureTaskRuntimeRunLoopOutputVerification.kt:78` and `:223`,
   `FeatureTaskRuntimeRunLoopTransitions.kt:119`, plus any `reconciled_state` reject site). Make
   an `implement` or `audit_implement_fix` completion that carries a claim acceptable with zero
   changed files. Outputs without a claim keep today's behavior. This step exists because the
   mutating-phase gates may otherwise block a correct no-change claim. (AC 3)
3. **Skip simplify.** Route an implement output that carries a claim directly to audit. This step
   exists so that branch-diff simplify cannot edit files under a claim of no change. (AC 3)
4. **Evidence audit.**
   - When the latest implementation output carries a claim, the audit round runs the validator
     before the remaining-criteria prose parser.
   - A structural rejection settles the round into `audit_implement_fix`, with the validator's
     reason as the fix input. The usual repair-retry accounting applies.
   - A structurally valid claim goes to the audit agent. The audit prompt resource tells it to
     read the cited locations and the trace without editing anything, and to answer
     `no_change_confirmed` or `no_change_rejected` with reasons.
   - Teach `AcceptanceAuditVerdictRule` and `completionRejection` both words, so that a
     confirmed claim is not rejected as satisfied-with-remaining-criteria.
   - `no_change_rejected` routes to `audit_implement_fix` with the auditor's reasons. The
     fix-step prompt says it may either make changes or return improved evidence.

   (AC 2, 4, 5)
5. **Pause artifact and stop.**
   - Persist `FeatureTaskRuntimeNoChangePause` as a child-workflow artifact with a strict
     `fromArtifactMap` and the exact field set. `operator_decision` and
     `operator_instructions` start as null.
   - On `no_change_confirmed`, stop the run loop without entering `audit_implement_fix`, and
     surface goal stop reason `AWAITING_NO_CHANGE_DECISION`.
   - Classify that stop reason next to `AWAITING_OPERATOR_DECISION` in every exhaustive `when`,
     and in `RESUMABLE_STOP_REASONS` wherever `AWAITING_OPERATOR_DECISION` appears there.

   (AC 4, 6, 8)
6. **Stop report and CLI line.** `GoalRunnerStopReports` renders the pause:
   - the reason;
   - each criterion's id, verdict and evidence;
   - the citations and the boundary trace;
   - the owning system when present, and the suggested handoff;
   - the three operator choices.

   The CLI prints an `awaiting_no_change_decision: ...` line in the `awaiting_confirmation`
   format. (AC 7)
7. **Telemetry and contracts.**
   - Add `awaiting_no_change_decision` to `goalRunnerStopReasonEnum`.
   - Add the optional `no_change_reason` field, taking the three reason words, to `goal_finished`
     and `goal_issue_finished`.
   - `goalFinishedStatus` maps the new stop reason to `paused` and sets `no_change_reason`.
   - Document `produced_outputs.no_change` in the phase-output schema, adding it to the implement
     `allOf` branch if that branch closes `produced_outputs`.
   - Leave the version consts unchanged.
   - Add the claim instructions to the implement prompt or directive resource.

   (AC 9, 10)

## Acceptance Criteria

1. A domain validator for `produced_outputs.no_change` exists. For an invalid claim it returns a
   rejection reason that names the specific failure:
   - an unknown or missing reason;
   - a catalog criterion with no entry, or with a blank verdict or blank evidence;
   - no `path:line` citation;
   - a blank boundary trace;
   - changed files in the step.

   For a valid claim it returns the claim, and it derives `suggested_handoff` for each reason when
   the claim omits it.
2. The audit round runs that validator before the remaining-criteria prose parser whenever the
   latest implementation output carries a claim. A structural rejection settles into
   `audit_implement_fix` with the validator's reason as the fix input. It never settles with the
   generic "Unidentified remaining criterion." reason.
3. An `implement` or `audit_implement_fix` completion that carries a claim and has zero changed
   files passes the mutating-phase output verification. An `implement` output that carries a claim
   moves to audit without running `simplify`. Outputs without a claim follow the same path as
   before.
4. The audit prompt resource tells the auditor to check the claim's citations and boundary trace
   read-only and to answer `no_change_confirmed` or `no_change_rejected` with reasons.
   `AcceptanceAuditVerdictRule` and `completionRejection` recognize both words.
5. An audit verdict of `no_change_rejected` routes to `audit_implement_fix` with the auditor's
   reasons as the fix input, and it consumes one repair retry like any other unmet round.
6. An audit verdict of `no_change_confirmed` persists a `no_change_pause` artifact and stops the
   run with goal stop reason `AWAITING_NO_CHANGE_DECISION`. `audit_implement_fix` is never
   entered, and the repair-retry count is the same as before the audit round.
7. `FeatureTaskRuntimeNoChangePause` has a strict `fromArtifactMap` with
   `record_kind: "no_change_pause"` and the exact field set from the parent Shared Contract. It
   reads back what its writer produces and rejects a map with an unknown field.
8. `GoalRunnerStopReason.AWAITING_NO_CHANGE_DECISION` exists, and every exhaustive `when` in
   `GoalRunnerTerminalModels.kt` and `GoalRunnerStopReports.kt` handles it the way it handles
   `AWAITING_OPERATOR_DECISION`. The pause ends the runner invocation, so no idle or wall-clock
   timer is running while it waits.
9. The goal stop report for this pause contains:
   - the reason;
   - every criterion's id, verdict and evidence;
   - the citations and the boundary trace;
   - the owning system when present, and the suggested handoff;
   - the operator choices `accept_and_advance`, `retry_fix` (with instructions) and
     `abandon_subtask`.

   The CLI prints an `awaiting_no_change_decision:` line in the `awaiting_confirmation` line
   format.
10. `telemetry-event-schema.yaml` lists `awaiting_no_change_decision` in
    `goalRunnerStopReasonEnum` and defines the optional `no_change_reason` field on
    `goal_finished` and `goal_issue_finished`. The emitter reports this pause with status `paused`
    and sets `no_change_reason`. The `"1.12.0"` const is unchanged.
11. `feature-task-runtime-phase-output-schema.yaml` documents `produced_outputs.no_change` with
    `contract_version` still `"0.7"`, and the implement prompt or directive tells implementation
    how and when to declare the claim.

## Test Obligations

- A table-driven validator test, with one row per rejection rule plus one valid claim (AC 1).
  It catches a claim missing a criterion verdict being accepted.
- A run-loop test (AC 3, 6). Implement emits a valid claim with zero changed files, and the audit
  returns `no_change_confirmed`. The test asserts that the run stops with the
  `no_change_pause` artifact and `AWAITING_NO_CHANGE_DECISION`, that simplify and
  `audit_implement_fix` were not entered, and that the retry count is unchanged. It catches the
  original WE-5006 loop.
- A run-loop test (AC 2, 5). An audit returning `no_change_rejected` with a reason settles into
  `audit_implement_fix`, whose input contains that reason. It catches a rejection that is
  silently treated as confirmation, or that loses its reason.
- A strict round-trip test for `FeatureTaskRuntimeNoChangePause`, including rejection of an
  unknown field (AC 7). It catches writer and reader field drift that would break resume.
- A stop-report rendering test that asserts the reason, each criterion's evidence and the
  handoff appear (AC 9).
- A telemetry test that the emitted `goal_finished` payload for this pause validates against the
  schema, with `status: paused` and `no_change_reason` set (AC 10).
- Update any fixture in `FeatureTaskRuntimePhaseOutputFixtures.kt` and any workflow-snapshot JSON
  under `runtime-engine/src/test/resources/featuretask/` only where the new paths change them.
  Use `relaxUnitFun = true`, never `relaxed = true`.

## Non-Goals

- Operator decisions on the pause, the `completed_no_change` terminal status, finalization, the
  dispatcher, and `goal status` completion output. These belong to subtask 2.
- Runtime filesystem checks of citations, and any check of other repositories.
- Changing the review-state schema or the review-remediation rejection.
- Bumping contract-version consts.

## Dependency Notes

This subtask has no manifest dependencies and runs on the current tree. Follow the parent
Execution Rule: if subtask 2 already added a Shared Contract element (for example
`no_change_reason`), keep it. Keep every uncommitted SKILL-407 edit in the shared contract and
workflow files.

## Assumptions to Confirm

- The site that enforces idle and wall-clock timeouts has not been located. AC 8 relies on the
  pause ending the runner invocation. If a timer survives that stop, exclude this stop reason
  from it.
- The `reconciled_state` reject site has not been located. If none exists, step 2 only touches
  the checks at the cited lines.
- `FeatureTaskRuntimeVerdict.kt` is assumed to hold the verdict vocabulary. If the words belong
  somewhere else, put them there.
- Where the step's changed-file set comes from: reuse the set the run loop already records
  (checkpoint path reconciliation at `FeatureTaskRuntimeRunLoopOutputVerification.kt:360-369`).

## Validation Strategy

The validate phase runs `./gradlew check`. Implement and audit inspect the tree only.

## Next Path

```bash
skill-bill goal SKILL-408
```
