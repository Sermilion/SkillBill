# SKILL-408 Subtask 1: No-change claim, evidence audit and operator pause

Parent: `spec.md`. Use the parent's
Shared Contract names exactly.

## Scope

Implementation can claim no change with evidence. The audit checks that evidence instead of the
diff. A confirmed claim stops the run with a persisted `no_change_pause` artifact and the goal
stop reason `awaiting_no_change_decision`, and it shows a report. A missing or rejected claim
goes through the normal repair loop and carries its reason with it. This subtask does not cover
operator decisions on the pause, which belong to subtask 2.

Path prefixes: DOM = `../../../runtime-kotlin/runtime-domain/src/main/kotlin/skillbill`,
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
- `../../../runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/audit/opus-5-5-acceptance-audit.md`,
  plus the implement-phase prompt or directive resource, wherever it lives.
- CON `feature-task-runtime-phase-output-schema.yaml` and `telemetry-event-schema.yaml` (both
  already have uncommitted edits). CON `workflow-state-schema.yaml` only if child artifacts list
  their record kinds there.
- The CLI goal-run output path that prints the stop line, matching the format of the
  `awaiting_confirmation` line at `OperationCommand.kt:34/181`.
- Tests under the matching `src/test` trees, including
  `../../../runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/featuretask/slot/FeatureTaskRuntimePhaseOutputFixtures.kt`
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

## Implementation Details

The preplan digest is the only repository knowledge behind this plan. Each "Confirm first" note
marks a fact the digest did not establish. Implement reads that site before editing. If the site
contradicts the plan, follow the "If not" branch, which keeps the acceptance criteria intact.

### Constraints for every task

- Keep every uncommitted SKILL-407 edit in `FeatureTaskRuntimeVerdict.kt`,
  `FeatureTaskRuntimePhaseWorkflowGraph.kt`, `FeatureTaskRuntimePhaseWorkflowTransitions.kt`,
  `FeatureTaskRuntimeRunLoopBackwardEdge.kt`, `feature-task-runtime-phase-output-schema.yaml`,
  `telemetry-event-schema.yaml` and `workflow-state-schema.yaml`. Add only this feature's lines.
  If subtask 2 has already added a Shared Contract element (`no_change_reason`,
  `AWAITING_NO_CHANGE_DECISION`, `FeatureTaskRuntimeNoChangePause`), keep it and reuse it.
- Shared Contract names and wire values are exact: `no_change`, `out_of_repo`,
  `already_satisfied`, `not_reproducible`, `no_change_confirmed`, `no_change_rejected`,
  `no_change_pause`, `awaiting_no_change_decision`, `no_change_reason`.
- Do not change the phase-output `"0.7"` const or the telemetry `"1.12.0"` const.
- Domain code stays pure: no `java.nio`, no ports imports, and no `skillbill.text` imports from
  model packages. Expected outcomes are returned as sealed results, not thrown. Use
  `require`/`check` only for programming defects.
- Follow the existing artifact pattern of `FeatureTaskRuntimeAuditGapPause`, including where its
  map writer lives, so the raw-map architecture guard stays green. If a package goes over the
  `PackageSiblingCountArchitectureTest` limit, put the new files in a subpackage instead.
- Mocks use `relaxUnitFun = true`, never `relaxed = true`.
- Implement does not run builds, tests or `check`. The validate phase owns them.

### Ordered tasks

**Task 1: claim model, lenient parser and structural validator (AC 1).**
- New DOM files in `workflow/taskruntime/model/audit/`, next to
  `FeatureTaskRuntimeAuditGapPersistenceModels.kt`:
  - `FeatureTaskRuntimeNoChangeClaim.kt`:
    - `enum class FeatureTaskRuntimeNoChangeReason(val wireValue: String)` with `OUT_OF_REPO`,
      `ALREADY_SATISFIED` and `NOT_REPRODUCIBLE`, plus a lenient `fromWireOrNull` that lowercases
      and maps `-` and spaces to `_`, so `Out-Of-Repo` reads as `out_of_repo`.
    - `data class FeatureTaskRuntimeNoChangeCriterion(criterionId, verdict, evidence)`.
    - `data class FeatureTaskRuntimeNoChangeClaim(reason, criteria, citations, boundaryTrace,
      owningSystem: String?, suggestedHandoff: String)`. `suggestedHandoff` is always resolved
      once validation has run.
  - `FeatureTaskRuntimeNoChangeClaimValidator.kt`: an object with
    `validate(raw: Map<String, Any?>, catalogCriterionIds: List<String>, changedFiles: Collection<String>): NoChangeClaimValidation`.
    `NoChangeClaimValidation` is a sealed type with `Valid(claim)` and `Rejected(reasons: List<String>)`.
    The validator collects every failure rather than stopping at the first. Each failure gets its
    own fixed message prefix, so tests and the fix step can name it:
    - `reason` is missing or not one of the three words;
    - a catalog criterion id has no entry, or the entry's verdict or evidence is blank, or its
      verdict is not one of the three words (one message per criterion id);
    - no citation matches `^\S+:\d+(-\d+)?$`;
    - `boundary_trace` is blank;
    - `changedFiles` is non-empty. The message lists the files, per Decision 7;
    - the map has a key outside the Shared Contract field set (strict field presence).
  - Derive the handoff when `suggested_handoff` is absent or blank:
    - `out_of_repo`: `"Hand off to <owning_system>"`, or `"Hand off to the owning system (not identified)"`
      when there is no owning system;
    - `already_satisfied`: `"Close the issue as already satisfied"`;
    - `not_reproducible`: `"Return the issue to the reporter for reproduction steps"`.
  - Add `FeatureTaskRuntimeNoChangeClaim.Companion.KEY = "no_change"` as the single
    `produced_outputs` key constant.
- Tests: add `FeatureTaskRuntimeNoChangeClaimValidatorTest` under the matching runtime-domain
  `src/test` package. It is one table-driven test:
  - one row per rejection rule (missing reason, unknown reason, missing criterion entry, blank
    evidence, no valid citation, blank trace, changed files, unknown field);
  - one valid row with a mixed-case reason;
  - one assertion of the derived handoff for each reason, including `out_of_repo` with and
    without an owning system.

  Realistic bug caught: a claim missing a criterion verdict is accepted, or a non-empty changed
  file set is silently accepted.

**Task 2: pause artifact model (AC 7).**
- New DOM `FeatureTaskRuntimeNoChangePause.kt`, in the package of `FeatureTaskRuntimeAuditGapPause`.
  - Fields: `reason`, `criteria`, `citations`, `boundaryTrace`, `owningSystem: String?`,
    `suggestedHandoff`, `auditSummary`, `operatorDecision: GoalSubtaskOperatorDecision?` and
    `operatorInstructions: String?`.
  - The writer emits `record_kind: "no_change_pause"` plus exactly those ten keys in snake case.
    `operator_decision` and `operator_instructions` are null on creation.
  - A strict `fromArtifactMap` requires the exact key set and the record kind. It parses
    `operator_decision` through the existing `GoalSubtaskOperatorDecision` wire values. On an
    unknown or missing key it fails the same way `FeatureTaskRuntimeAuditGapPause.fromArtifactMap`
    does.
  - Add a `fromClaim(claim, auditSummary)` factory.
- Confirm first: the AuditGapPause artifact-map pattern and the store API, starting from
  `FeatureTaskRuntimeWorkflowArtifactMap` in `workflow/taskruntime/model/core/`. Also confirm
  that domain may import `GoalSubtaskOperatorDecision` (`workflow/model/goalreview/`). If it
  cannot, store `operatorDecision` as a validated wire string with the same four permitted
  values.
- Tests: add `FeatureTaskRuntimeNoChangePauseTest` with two tests:
  - a round trip, writer to `fromArtifactMap`, on a pause with an owning system and one without;
  - rejection of a writer map with one extra key.

  Realistic bug caught: writer and reader field drift that breaks resume in subtask 2.

**Task 3: verdict words (AC 4).**
- Confirm first that `FeatureTaskRuntimeVerdict.kt` holds the audit verdict vocabulary. Add
  `NO_CHANGE_CONFIRMED("no_change_confirmed")` and `NO_CHANGE_REJECTED("no_change_rejected")`
  there, next to the SKILL-407 edits. If the vocabulary lives elsewhere, add the words there.
- ENG `featuretask/slot/audit/AcceptanceAuditVerdictRule.kt`: read `removedVerdictRejection`
  first. Make sure both new words are accepted, not rejected as removed or unknown, and only
  when the latest implementation output carries a claim. A no-change word on a round without a
  claim is rejected with a specific message.
- `AcceptanceAuditRound.completionRejection` (lines 25–53): both words skip the
  `AcceptanceAuditProgress.declaresComplete` requirement, so `no_change_confirmed` is not rejected
  as satisfied-with-remaining-criteria. `no_change_rejected` must carry non-blank prose reasons,
  or it is rejected like an empty unmet round.
- Tests: none of their own. The Task 6 run-loop tests exercise both words through the real rule.

**Task 4: mutating-phase verification accepts a claim with zero changed files (AC 3).**
- ENG `featuretask/runloop/output/FeatureTaskRuntimeRunLoopOutputVerification.kt`, lines 78
  and 223: when the phase is `implement` or `audit_implement_fix`, `produced_outputs` contains
  `no_change`, and the reconciled changed-file set (lines 360–369) is empty, the zero-change
  rejection does not fire.
- The runtime does not judge the claim's structure here. Task 6 does that, so a malformed claim
  still reaches audit and gets a specific reason instead of a generic verification failure.
- If a claim arrives with changed files, verification keeps today's behavior. The validator then
  rejects the claim in audit under Decision 7.
- Confirm first: search `FeatureTaskRuntimeRunLoopTransitions.kt:119`, `slot/PhaseStepHooks.kt`,
  `slot/attempt/PhaseAttemptOnce.kt` and `slot/attempt/PhaseAttemptContinuations.kt` for a
  zero-diff or `reconciled_state` reject. Apply the same claim exemption at every site found. If
  none exists, record that in the implement output.
- Outputs without a claim follow the same code path as before.
- Tests: covered by the Task 6 confirmed-path test, which drives an implement with zero changed
  files through verification. The existing verification tests guard the no-claim path.

**Task 5: skip simplify on a claim (AC 3).**
- Where the run loop picks the phase after `implement` (`FeatureTaskRuntimeRunLoopTransitions.kt`
  around line 119): if the implement output carries `no_change`, the next phase is `audit`.
- Confirm first: whether `FeatureTaskRuntimePhaseWorkflowGraph.kt` already permits an
  `implement` to `audit` edge. If it does not, add that edge in the graph and in
  `FeatureTaskRuntimePhaseWorkflowTransitions.kt`, and keep the SKILL-407 edits. If it does,
  change only the run-loop selection.
- Tests: assert in the Task 6 confirmed-path test that simplify never ran.

**Task 6: evidence audit routing (AC 2, 4, 5, 6).**
- ENG `AcceptanceAuditRound.kt` `settleCompletedRound` and `progressRejection` (68–112), and ENG
  `featuretask/runloop/attempt/FeatureTaskRuntimeRunLoopAuditSettlement.kt`:
  1. Resolve the latest `implement` or `audit_implement_fix` output. If it carries `no_change`,
     run `FeatureTaskRuntimeNoChangeClaimValidator.validate`. Pass it the run's
     `AcceptanceAuditCatalog` criterion ids and the step's reconciled changed-file set. This
     happens before `AcceptanceAuditRemainingCriteria` parsing.
  2. On `Rejected`, settle into `audit_implement_fix`. The fix input is the joined validator
     reasons, prefixed `No-change claim rejected:`. This consumes one repair retry through the
     normal `AUDIT_REPAIR_LOOP_ID` path. The remaining-criteria parser is not called, so
     "Unidentified remaining criterion." cannot appear.
  3. On `Valid`, read the audit agent's verdict:
     - `no_change_rejected`: settle into `audit_implement_fix`. The fix input is the auditor's
       prose reasons. This consumes one repair retry like any unmet round.
     - `no_change_confirmed`: build `FeatureTaskRuntimeNoChangePause.fromClaim(claim,
       auditSummary = audit prose value)` and persist it as a child-workflow artifact. Settle the
       round as a terminal no-change pause. This returns before any repair-loop iteration is
       read or incremented, so the retry count stays the same.
     - any other verdict: the round is rejected as an invalid verdict for a claim-bearing round.
- Confirm first: how `AcceptanceAuditStrategy.kt` reaches the latest implementation
  `produced_outputs`, the catalog, and the changed-file set. If the changed-file set is not
  reachable at audit time, record it on the implement step at verification time (Task 4) and
  read it back here.
- The run loop's terminal outcome for a confirmed pause must end the child run, the same way
  an existing paused or blocked child outcome does. It is not an in-flight wait.
- Fix-step prompt: in the `audit_implement_fix` prompt or directive resource, wherever it lives,
  add two sentences. When the fix input starts `No-change claim rejected:` or comes from a
  `no_change_rejected` audit, the step either makes the needed changes or returns an improved
  `no_change` claim. It must not do both.
- Tests: add them in the audit run-loop tests under `featuretask/runner/`, reusing
  `FeatureTaskRuntimePhaseOutputFixtures.kt`. Add a claim fixture helper there if none exists.
  - **Confirmed path (AC 3, 5 to 6).** Implement emits a valid claim with zero changed files,
    and audit returns `no_change_confirmed`. Assert that:
    - a `no_change_pause` artifact exists and its `criteria` match the claim;
    - simplify and `audit_implement_fix` never ran;
    - the repair-loop iteration equals its value before the audit round;
    - the child run ended.

    Realistic bug caught: the WE-5006 loop, or the retry counter being incremented on
    confirmation.
  - **Rejected paths, one parameterized test (AC 2, 5).** Row A is a claim with no valid citation,
    which is a structural rejection. Row B is a valid claim with an audit verdict of
    `no_change_rejected` and the reason "trace contradicts web/report.ts:40". For each row, assert
    that:
    - the next phase is `audit_implement_fix`;
    - its input contains the specific reason;
    - it does not contain "Unidentified remaining criterion.";
    - the retry count went up by one.

    Realistic bug caught: a rejection that is treated as a confirmation, or a rejection that
    loses its reason.
  - Update a workflow-snapshot JSON under `runtime-engine/src/test/resources/featuretask/` only if
    a new path changes it.

**Task 7: audit prompt resource (AC 4).**
- `runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/audit/opus-5-5-acceptance-audit.md`:
  add a short section for claim-bearing rounds. When the implementation output carries
  `produced_outputs.no_change`:
  - the diff is expected to be empty;
  - read every cited `path:line` and the boundary trace without editing anything;
  - do not resolve citations outside this worktree;
  - answer `no_change_confirmed` when the evidence supports every criterion's verdict;
  - otherwise answer `no_change_rejected`, with the specific reasons in the prose value.
- Tests: none. This is prompt text that audit inspects.

**Task 8: stop reason and goal-runner stop (AC 6, 8).**
- DOM `goalrunner/model/GoalRunnerTerminalModels.kt`:
  - add `AWAITING_NO_CHANGE_DECISION("awaiting_no_change_decision")` to `GoalRunnerStopReason`;
  - add it to `RESUMABLE_STOP_REASONS` (line 46);
  - give it the same branch as `AWAITING_OPERATOR_DECISION` in `toLedgerAction` (141–155),
    `toDiagnosticClass` (157–172) and `nextSafeAction` (174–189). `nextSafeAction` names the
    `goal operator-decision` command.
- ENG `goalrunner/status/GoalRunnerStopReports.kt` `supervisionEvent` (126–139): same branch as
  `AWAITING_OPERATOR_DECISION`.
- Every other `when` over `GoalRunnerStopReason` that handles `AWAITING_OPERATOR_DECISION` gets
  the matching branch, so the build stays exhaustive: `runtime-cli/.../goal/core/GoalCliExitCodes.kt`,
  `runtime-ports/.../idestatus/model/IdeStatusModels.kt`, `runtime-engine/.../work/IdeStatusProjector.kt`
  and `runtime-domain/.../goalrunner/GoalRunnerPolicy.kt`. Use the same exit code and IDE status
  as the operator-decision pause.
- Stop propagation: in `GoalRunnerSelectedSubtaskLoop.kt`, modeled on `GoalRunnerPauseBoundary.kt`.
  When the child run ends and the child workflow holds a `no_change_pause` artifact whose
  `operator_decision` is null, return `stopped(StoppedReportArgs(reason = AWAITING_NO_CHANGE_DECISION, ...))`.
  `driveGoalLoop` (`GoalRunnerGoalLoop.kt` 42–87) then exits on the non-null `terminalReport`.
  The subtask stays non-terminal in the manifest.
- Confirm first: where the idle and wall-clock timers are armed. They must be scoped to the
  runner invocation that just returned. If a timer outlives the stop, exclude this stop reason
  from it.
- Tests: covered by Task 9's report test, which builds the stop report from this path, and by
  compile-time exhaustiveness. A separate enum-mapping test would only mirror the `when`
  branches, so none is added.

**Task 9: stop report and CLI line (AC 9).**
- `GoalRunnerStopReports.kt`: when the reason is `AWAITING_NO_CHANGE_DECISION`, load the pause
  artifact and render, in this order:
  - the reason word;
  - one line per criterion: id, verdict and evidence;
  - the citations;
  - the boundary trace;
  - `owning_system`, only when non-null;
  - the suggested handoff;
  - the operator choices `accept_and_advance`, `retry_fix --instructions "<text>"` and
    `abandon_subtask`, each as a `goal operator-decision` invocation.

  Put the payload in whatever detail or field structure `StoppedReportArgs` already offers. Do
  not add a new report variant (that is subtask 2's `GoalRunnerRunReport` work).
- CLI: in `runtime-cli/.../goal/run/GoalRunPresenter.kt`, or wherever the goal-run stop line is
  printed, print `awaiting_no_change_decision: <summary>`. Copy the exact line shape that
  `OperationCommand.kt:34/181` uses for `awaiting_confirmation`.
- Tests: add one stop-report rendering test in the `goalrunner/status` test package. Build it
  from a pause with an owning system, and assert that the reason, each criterion's evidence,
  one citation, the trace, the owning system, the handoff and all three choices appear. Then
  assert, in the existing presenter test if there is one (otherwise in the same test), that the
  CLI line starts `awaiting_no_change_decision:`.

  Realistic bug caught: a report that drops evidence, leaving the operator to decide blind.

**Task 10: telemetry (AC 10).**
- CON `telemetry-event-schema.yaml`:
  - add `awaiting_no_change_decision` to `goalRunnerStopReasonEnum`;
  - add an optional `no_change_reason` property (an enum of the three reason words, nullable if
    sibling optionals are nullable) to `goal_finished` and to `goal_issue_finished`;
  - leave `"1.12.0"` unchanged;
  - if subtask 2 already added any of these, keep its version.
- ENG `goalrunner/telemetry/GoalRunnerTelemetryEmitter.kt`: `goalFinishedStatus` maps
  `AWAITING_NO_CHANGE_DECISION` to `paused`. Both events set `no_change_reason` from the pause
  artifact's reason, and omit it otherwise.
- Tests: add one emitter test. It emits `goal_finished` for this pause and validates the payload
  against `telemetry-event-schema.yaml` with the existing schema-validation helper. It asserts
  `status: paused` and the `no_change_reason` value.

  Realistic bug caught: a payload that the schema rejects, which drops the event downstream.

**Task 11: phase-output contract and implement guidance (AC 11).**
- Confirm first: read `$defs.phaseProseProducedOutputs` in CON
  `feature-task-runtime-phase-output-schema.yaml` (lines 1–194).
  - If it allows non-string values, document `no_change` in the `produced_outputs` description.
  - If it restricts values to strings, add `no_change` as an optional object property, with the
    Shared Contract shape, in that def or in the implement `allOf` branch.
  - Either way, `contract_version` stays `"0.7"` and the SKILL-407 edits stay.
- Implement prompt or directive resource, wherever the implement-phase prompt lives: add a short
  section. Declare `produced_outputs.no_change` only when the repository needs no change. List
  the fields and the three reasons, require one criterion entry per acceptance criterion and at
  least one `path:line` citation, and leave the diff empty. Settle with status `completed` and a
  prose value as usual.
- Tests: none of their own. The validate phase's schema and contract checks cover the YAML.

### Wave note

This plan is one file, so all of it was planned in a single settled pass, and no fan-out wave was
needed.

## Validation Strategy

The validate phase runs `./gradlew check`. Implement and audit inspect the tree only.

## Next Path

```bash
skill-bill goal SKILL-408
```
