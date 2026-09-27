# SKILL-380 Subtask 5 - Step-identity census

Scope: production Kotlin under
`../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask`.
The baseline is `d14217a8f`. The final tree is the working tree on top of
`986d2ab8a`, untracked files included.

## Patterns

Each pattern approximates one form that `FeatureTaskStepIdentityScan` rejects
(`runtime-core/src/repoTest/kotlin/skillbill/architecture/FeatureTaskSlotBoundaryScans.kt`).
Every count comes from `git grep -c -P` (`--untracked` for the final tree), with
the pathspec `':(top)runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/*.kt'`.

| Form | Scan rule | Pattern |
| --- | --- | --- |
| F1 constant | `PHASE_*` or `FeatureTaskRuntimePhaseIds` member, qualified, aliased, or imported | `\bPHASE_(PREPLAN\|PLAN\|IMPLEMENT\|SIMPLIFY\|AUDIT\|REVIEW\|VERIFY_FINDINGS\|IMPLEMENT_FIX\|BUILD\|VALIDATE\|WRITE_HISTORY\|COMMIT_PUSH\|PR)\b\|FeatureTaskRuntimePhaseIds\s*\.\s*[A-Z_]+` |
| F2 literal | string literal equal to a step id | `"(preplan\|plan\|implement\|simplify\|audit\|review\|verify_findings\|implement_fix\|build\|validate\|write_history\|commit_push\|pr)"` |
| F3 element access | element access on a slot's `steps` or `stepIds` | `\.\s*(steps\|stepIds)\s*(\[\|\.\s*(first\|last\|single\|get\|elementAt\|getOrNull)\b)` |
| F4 single-step alias | a declaration whose value is one step id | `val \w+(: String)? = *([\w.]*[.])?(PHASE_[A-Z_]+\|steps[.](first\|single))`, with each hit then checked by hand for visibility |

Each F1 hit counts once per line. F1 names the step constants explicitly, so
the shared-prefix false positives listed below never match.

## Step-owned scope (`STEP_OWNED_PACKAGES`: `phase/`, `lifecycle/`)

| Package | F1 d14217a8f | F1 final | F2 d14217a8f | F2 final | F3 d14217a8f | F3 final | F4 d14217a8f | F4 final |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `phase/` | 72 | 0 | 1 | 0 | 0 | 0 | 0 | 0 |
| `lifecycle/` | 19 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

At `d14217a8f`, the `phase/` F1 hits were:

- 53 in `phase/prompt/directives`
- 8 in `phase/core`
- 4 in `phase/briefing`
- 3 in `phase/planning`
- 2 in `phase/prompt/compose`
- 2 in `phase/record`

The F2 hit was in `phase/core/FeatureTaskRuntimePhaseSafetyPolicy.kt`.

## Remainder outside the step-owned scope (subtask 7)

These packages are not in `STEP_OWNED_PACKAGES` yet. Subtask 7 widens the rule
to every package outside `slot` and removes the remaining hits.

| Package | F1 d14217a8f | F1 final | F2 final | F3 final | F4 final (local vals) |
| --- | --- | --- | --- | --- | --- |
| `runloop/core` | 4 | 0 | 0 | 0 | 0 |
| `runloop/phase` | 6 | 6 | 0 | 0 | 0 |
| `runloop/output` | 9 | 8 | 1 | 0 | 0 |
| `runloop/state` | 27 | 26 | 0 | 0 | 5 |
| `runloop/settlement` | 3 | 1 | 0 | 0 | 0 |
| `runner` | 13 | 13 | 0 | 2 | 2 |
| `review` | 10 | 7 | 0 | 0 | 0 |
| `validation` | 7 | 7 | 4 | 0 | 0 |
| `persist` | 3 | 0 | 0 | 1 | 0 |

Per-file remainder:

- `runloop/phase`: `FeatureTaskRuntimeRunLoopPhaseBlocking.kt` (3) and
  `FeatureTaskRuntimeRunLoopPreLaunch.kt` (3).
- `runloop/output`:
  - F1: `FeatureTaskRuntimeRunLoopOutputVerification.kt` (2),
    `FeatureTaskRuntimeRunLoopRecordRejection.kt` (5), and
    `FeatureTaskRuntimeRunLoopReviewCompletion.kt` (1).
  - F2: `FeatureTaskRuntimeRunLoopRepairReceipt.kt` (1).
- `runloop/state`:
  - F1: `FeatureTaskRuntimeRunState.kt` (9),
    `FeatureTaskRuntimeRunStateReconstruction.kt` (8),
    `FeatureTaskRuntimeRunStateValidation.kt` (4), `FeatureTaskRuntimeAttemptBudgets.kt` (2),
    `FeatureTaskRuntimeRunLoopSkeletonPhaseRunState.kt` (2), and
    `FeatureTaskRuntimeRunPreparation.kt` (1).
  - F4 local vals: `reviewPhaseId`, `fixPhaseId`, `auditPhaseId`, and two
    `auditPhase`.
- `runloop/settlement`: `FeatureTaskRuntimeRunLoopValidationScope.kt` (1).
  `FeatureTaskRuntimeRunLoopAuditRetry.kt` was deleted in `986d2ab8a`; its retry
  settlement moved to `slot/audit/AcceptanceAuditRound.kt`.
- `runner`:
  - F1: `FeatureTaskRuntimeRunnerLaunchOutcomes.kt` (5),
    `FeatureTaskRuntimeStatusService.kt` (3), `FeatureTaskRuntimeRunnerPolicies.kt` (2),
    `FeatureTaskRuntimeStatusServicePhaseResolution.kt` (2), and
    `FeatureTaskRuntimeRunnerExecute.kt` (1).
  - F3: `FeatureTaskRuntimeRunnerExecutePrepared.kt` (2:
    `PhaseSlot.PREPLAN.steps.first()` and `PhaseSlot.COMMIT_PUSH.steps.first()`, landed in `986d2ab8a`).
  - F4 local vals: `plan` and `preplan`.
- `review`: `FeatureTaskRuntimeReviewGenerationRecorder.kt` (4),
  `FeatureTaskRuntimeOutputVerification.kt` (2), and
  `FeatureTaskRuntimeGoalReviewCompletionRecorder.kt` (1).
- `validation`:
  - F1: `FeatureTaskRuntimeBuildGateCoordinator.kt` (3),
    `FeatureTaskRuntimeValidationGateCoordinator.kt` (2),
    `FeatureTaskRuntimeReadinessGateCoordinator.kt` (1), and
    `FeatureTaskRuntimeValidationGatePolicy.kt` (1).
  - F2: readiness source labels `"commit_push"` and `"validate"` (4).
- `persist`: F3 in `FeatureTaskRuntimeWorkflowPersistence.kt`
  (`definition.stepIds.last()`).

The AuditRetry hint plumbing that stays for subtask 7:

- `runloop/core/FeatureTaskRuntimeRunLoopLaunch.kt`: the `AuditRetry` attempt
  result, `auditRetryFocusHint`, and `auditRetryContinuation`.
- `runloop/core/FeatureTaskRuntimeRunLoopSession.kt`: the focus-hint storage and
  `transitionAuditRetryFocusHint`.
- `slot/attempt/PhaseAttemptContinuations.settleAuditRetry` and
  `slot/attempt/PhaseAttemptLoop`.
- The `run.phaseId == PHASE_AUDIT` gate in `slot/attempt/PhaseLaunchPreparation.kt`
  that fills `FeatureTaskRuntimePhasePromptComposeInputs.auditRetryFocusHint`.

The retry prompt text itself now lives in `slot/audit/AcceptanceAuditPromptSections.kt`.

`slot/` itself (strategies, `attempt`, `runner`) is the owning scope and is not
restricted by the step-identity rule. Its F1 count went from 38 to 57 because
step code moved into the strategies.

## False positives, excluded

F1 names the step constants explicitly, so none of these shared-prefix names
are counted:

- `PHASE_RECEIPT`
- `FEATURE_TASK_RUNTIME_PHASE_*` (contract and schema constants)
- `PHASE_ID` keys (`SharedPayloadKeys.PHASE_ID` and similar)
- `PHASE_PROMPT_TEMPLATE_INDENT`
- the settlement tool constants (`feature_task_phase_complete` and
  `feature_task_phase_block` names)
- `../../../agent/history.md` prose under the package root; the `*.kt` pathspec
  excludes it.

## Scope-table behaviour to owning slot file

| Behaviour | Owning file |
| --- | --- |
| Prose settlement specifics, ceremony line | `slot/preplan/AgentPreplanStrategy.kt` |
| Goal-continuation constraint | `slot/plan/AgentPlanStrategy.kt` |
| `applyPlanningStop` / decomposition stop | `slot/plan/PlanDecompositionStop.kt` |
| Implement and simplify continuation segments, `implementationContinuationDirective`, simplify scope boundary | `slot/implementation/ImplementationPromptSections.kt` |
| `implementation_receipt` / `simplification_receipt` checks | `slot/implementation/ImplementThenSimplifyStrategy.kt` |
| In-phase remaining-criteria retry (settlement) | `slot/audit/AcceptanceAuditRound.kt` |
| Remaining-criteria retry prompt and briefing rewrite | `slot/audit/AcceptanceAuditPromptSections.kt` |
| Unchanged-remainder block | `slot/audit/AcceptanceAuditRound.kt` (`auditRemainingUnchangedBlockReason`) |
| `gaps_found` rejection, audit verdict rule | `slot/audit/AcceptanceAuditVerdictRule.kt` |
| Audit-to-review checkpoint | `slot/audit/AcceptanceAuditLoopRules.kt` (`forwardCheckpoint`) |
| FINALIZATION briefing field set, write_history directive | `slot/writehistory/BoundaryHistoryStrategy.kt` |
| `history_result` replacement: measured changed paths, history and decision writes | `slot/writehistory/WriteHistoryMeasurement.kt` |
| `runDeclaredCommitPushCycle` | `slot/commitpush/RuntimeCommitCycle.kt` |
| Upstream-head fallback | `slot/commitpush/RuntimeCommitUpstreamHeadFallback.kt` |
| `verifyPrEntryIdentity` readiness gate | `slot/pullrequest/PullRequestReadinessGate.kt` |
| `pr_result` replacement: measured PR URL, number, created | `slot/pullrequest/PullRequestMeasurement.kt` |

## Notes

- `AcceptanceAuditRound.auditRemainingUnchangedBlockReason` has no production
  caller. It moved from `runloop/state/FeatureTaskRuntimeAttemptBudgets.kt`
  byte-for-byte and was not wired up. Only `FeatureTaskRuntimeAttemptBudgetsTest`
  calls it.
- No runtime gate, check, or fallback was removed. Each moved rule runs at the
  same point with the same text:
  - The `gaps_found` rejection runs through the step hook
    `completionRejection`, in the same order the output verification ran it.
  - The audit-to-review checkpoint runs through
    `PhaseLoopRules.forwardCheckpoint`.
  - The PR readiness gate runs at the start of `PrDescriptionStrategy.runStep`.
  - The audit retry prompt runs through `PhaseStepPromptSections.retryFocus`
    and `briefingRewrite`.
- No production reader decodes `pr_result`. The goal stop reports and
  `GhGoalPullRequestPort` query `gh` directly. `pr_description_generated`
  telemetry takes its own tool arguments. The status service reads step status
  only. A pre-change `produced_outputs.pr_result` therefore has no reader and
  reads as unknown.
