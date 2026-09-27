# SKILL-380 Subtask 7 census

Counted on 2026-09-27 over `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask`.
Paths below are relative to that root.

## Before

### Durable collaborators referenced by the run loop and strategies

References to `FeatureTaskRuntimePhaseRecorder`, `FeatureTaskRuntimeGoalContinuationRecorder`,
`FeatureTaskPhaseSettlementService`, `AgentActivityStampWriter`, `WorktreeEditJournalWriter`, and
`FeatureTaskRuntimeRunInvariantsStore`, together with the variables that hold them (`recorder`,
`goalContinuationRecorder`, `phaseSettlementService`, `activityStampWriter`, `worktreeEditJournalWriter`,
`runInvariantsStore`), under `runloop/` and `slot/`: 377 references in 31 files.

| File | References |
| --- | --- |
| slot/attempt/PhaseOutputGate.kt | 59 |
| slot/attempt/PhaseAttemptContinuations.kt | 44 |
| runloop/state/FeatureTaskRuntimeRunLoopSkeletonPhaseRunState.kt | 41 |
| runloop/phase/FeatureTaskRuntimeRunLoopPhaseBlocking.kt | 40 |
| slot/attempt/PhaseLaunchPreparation.kt | 25 |
| runloop/checkpoint/FeatureTaskRuntimeRunLoopCheckpoint.kt | 15 |
| runloop/output/FeatureTaskRuntimeRunLoopReviewCompletion.kt | 13 |
| slot/commitpush/RuntimeCommitCycle.kt | 13 |
| runloop/output/FeatureTaskRuntimeRunLoopOutputVerification.kt | 12 |
| runloop/core/FeatureTaskRuntimeRunLoopPlanningBranch.kt | 11 |
| runloop/settlement/FeatureTaskRuntimeRunLoopValidationScope.kt | 10 |
| runloop/state/FeatureTaskRuntimeRunPreparation.kt | 7 |
| slot/qualitygate/QualityGateSteps.kt | 7 |
| slot/attempt/PhaseAttemptLoop.kt | 6 |
| runloop/core/FeatureTaskRuntimeRunLoopModels.kt | 6 |
| slot/attempt/PhaseAttemptArgs.kt | 6 |
| runloop/core/FeatureTaskRuntimeRunLoop.kt | 6 |
| runloop/core/FeatureTaskRuntimeRunLoopSharedArgs.kt | 5 |
| runloop/core/FeatureTaskRuntimeRunLoopBackwardEdge.kt | 4 |
| runloop/output/FeatureTaskRuntimeRunLoopOutputPersistence.kt | 4 |
| slot/attempt/PhaseAttemptOnce.kt | 4 |
| slot/qualitygate/agentvalidate/AgentValidateGateCycle.kt | 3 |
| slot/qualitygate/packbuild/PackBuildGateCycle.kt | 3 |
| slot/audit/AcceptanceAuditRound.kt | 2 |
| runloop/phase/FeatureTaskRuntimeRunLoopPreLaunch.kt | 2 |
| runloop/core/FeatureTaskRuntimeRunLoopSubtaskCommit.kt | 2 |
| slot/pullrequest/PrDescriptionStrategy.kt | 2 |
| runloop/checkpoint/FeatureTaskRuntimeRunLoopCheckpointRemediation.kt | 2 |
| runloop/observability/FeatureTaskRuntimeRunObservability.kt | 1 |
| slot/plan/PlanDecompositionStop.kt | 1 |
| runloop/observability/FeatureTaskRuntimeRunObservabilityExtensions.kt | 1 |

Checkpoint git operations: the run loop reaches the checkpoint-ref and amend operations
(`amendHeadCommit`, `updateCheckpointRef`, `resolveCheckpointRef`) through one call,
`writeSubtaskCommitPreservingHistory`, at `runloop/checkpoint/FeatureTaskRuntimeRunLoopCheckpoint.kt:527`.

### Census items and where the run loop touched them

| Item | Run-loop access before |
| --- | --- |
| Workflow snapshot and step status | `recorder.recordPhaseState`, `recordCompletedPhase`, `loadPhaseRecords`, `ensureWorkflowOpen` |
| Phase records and ledger entries | `recorder.appendLedgerEntry`, `loadPhaseLedger`, `appendQuarantineEntry`, `recordIncompleteImplementationAttempt` |
| Run invariants | `FeatureTaskRuntimeRunInvariantsStore.resolve` in `runloop/state/FeatureTaskRuntimeRunPreparation.kt` |
| Runtime session rows | `recorder.ensureWorkflowOpen` in `runloop/state/FeatureTaskRuntimeRunPreparation.kt` |
| Checkpoints | `writeSubtaskCommitPreservingHistory`, `recorder.appendCheckpointIdentity`, `loadCheckpointIdentities`, `recordWorkflowOwnedPaths` |
| Resume reconstruction | `FeatureTaskRuntimeRunState` built from `recorder.loadPhaseRecords`, `loadPhaseLedger`, `reconcileReviewGeneration` in the runner |
| Goal continuation reads | `goalContinuationRecorder.reviewState`, `lastGoalReviewResult`, `buildGoalReviewInput`, `updateReviewState`, `reserveGoalReviewPass`, `completeGoalReviewPass` |
| Activity stamps, liveness, leases | `activityStampWriter.sink`, `worktreeEditJournalWriter.observer` in the skeleton run state |
| Lifecycle telemetry | `phaseGates.lifecycleTelemetry` in the runner only |

### Slot files referencing `FeatureTaskRuntimeRunLoopContext`

37 files (32 on 2026-09-26, plus subtask 6's delegated review and the files it touched):
slot/PhaseLoopRules.kt, slot/PhaseStepHooks.kt, slot/PhaseStrategy.kt, slot/attempt/PhaseAttemptArgs.kt,
slot/attempt/PhaseAttemptContinuations.kt, slot/attempt/PhaseAttemptLoop.kt, slot/attempt/PhaseAttemptOnce.kt,
slot/attempt/PhaseLaunchPreparation.kt, slot/attempt/PhaseOutputGate.kt, slot/attempt/PhaseStrategySteps.kt,
slot/audit/AcceptanceAuditRound.kt, slot/audit/AcceptanceAuditStrategy.kt, slot/codereview/CodeReviewSlot.kt,
slot/codereview/CodeReviewStep.kt, slot/codereview/DelegatedReviewStrategy.kt, slot/codereview/ImplementFixReceipt.kt,
slot/codereview/ImplementFixStep.kt, slot/codereview/InlineReviewLoopRules.kt,
slot/codereview/InlineReviewPreparation.kt, slot/codereview/InlineReviewStrategy.kt,
slot/codereview/VerifyFindingsEvidence.kt, slot/codereview/VerifyFindingsStep.kt,
slot/commitpush/RuntimeCommitCycle.kt, slot/commitpush/RuntimeCommitStrategy.kt,
slot/commitpush/RuntimeCommitUpstreamHeadFallback.kt, slot/implementation/ImplementThenSimplifyStrategy.kt,
slot/plan/AgentPlanStrategy.kt, slot/plan/PlanDecompositionStop.kt, slot/preplan/AgentPreplanStrategy.kt,
slot/pullrequest/PrDescriptionStrategy.kt, slot/qualitygate/QualityGateSteps.kt,
slot/qualitygate/agentvalidate/AgentValidateGateCycle.kt, slot/qualitygate/agentvalidate/AgentValidateStepHooks.kt,
slot/qualitygate/agentvalidate/AgentValidateStrategy.kt, slot/qualitygate/packbuild/PackBuildGateCycle.kt,
slot/qualitygate/packbuild/PackBuildStrategy.kt, slot/writehistory/BoundaryHistoryStrategy.kt.

### Step-identity references outside `slot`

The four forms of `FeatureTaskStepIdentityScan`, over every package outside `slot`: 76 references.
By top-level package: persist 1, review 7, runloop 42, runner 15, validation 11. `phase/` and `lifecycle/`
(the subtask 5 scope) had 0.

| File | Line: reference |
| --- | --- |
| persist/FeatureTaskRuntimeWorkflowPersistence.kt | 206: `definition.stepIds.last` |
| review/core/FeatureTaskRuntimeOutputVerification.kt | 30: `PHASE_REVIEW`; 31: `PHASE_VERIFY_FINDINGS` |
| review/core/FeatureTaskRuntimeReviewGenerationRecorder.kt | 39, 43, 52, 95: `PHASE_REVIEW` |
| review/goal/FeatureTaskRuntimeGoalReviewCompletionRecorder.kt | 267: `PHASE_REVIEW` |
| runloop/output/FeatureTaskRuntimeRunLoopOutputVerification.kt | 121: `PHASE_VALIDATE`; 224: `PHASE_BUILD` |
| runloop/output/FeatureTaskRuntimeRunLoopRecordRejection.kt | 145–149: `PHASE_IMPLEMENT`, `PHASE_SIMPLIFY`, `PHASE_IMPLEMENT_FIX`, `PHASE_VALIDATE`, `PHASE_WRITE_HISTORY` |
| runloop/output/FeatureTaskRuntimeRunLoopRepairReceipt.kt | 30: `"implement_fix"` |
| runloop/output/FeatureTaskRuntimeRunLoopReviewCompletion.kt | 39: `PHASE_REVIEW` |
| runloop/phase/FeatureTaskRuntimeRunLoopPhaseBlocking.kt | 336, 353, 402: `PHASE_REVIEW` |
| runloop/phase/FeatureTaskRuntimeRunLoopPreLaunch.kt | 120, 135: `PHASE_REVIEW`; 142: `PHASE_IMPLEMENT` |
| runloop/settlement/FeatureTaskRuntimeRunLoopValidationScope.kt | 43: `PHASE_BUILD` |
| runloop/state/FeatureTaskRuntimeAttemptBudgets.kt | 13, 47: `PHASE_VALIDATE` |
| runloop/state/FeatureTaskRuntimeRunLoopSkeletonPhaseRunState.kt | 473, 480: `PHASE_BUILD` |
| runloop/state/FeatureTaskRuntimeRunPreparation.kt | 44: `PHASE_AUDIT` |
| runloop/state/FeatureTaskRuntimeRunState.kt | 51: `PHASE_REVIEW`; 123: `PHASE_PLAN`; 153, 154: `PHASE_REVIEW`; 250: `PHASE_REVIEW`; 251: `PHASE_IMPLEMENT_FIX`; 274: `PHASE_AUDIT`; 314, 359: `PHASE_REVIEW` |
| runloop/state/FeatureTaskRuntimeRunStateReconstruction.kt | 44: `PHASE_AUDIT`; 147: `PHASE_IMPLEMENT`; 176, 183, 204, 222, 230, 237: `PHASE_AUDIT` |
| runloop/state/FeatureTaskRuntimeRunStateValidation.kt | 37, 38, 76, 77: `PHASE_VALIDATE` |
| runner/FeatureTaskRuntimeRunnerExecute.kt | 83: `PHASE_VERIFY_FINDINGS` |
| runner/FeatureTaskRuntimeRunnerExecutePrepared.kt | 100: `PhaseSlot.PREPLAN.steps.first`; 145: `PhaseSlot.COMMIT_PUSH.steps.first` |
| runner/FeatureTaskRuntimeRunnerLaunchOutcomes.kt | 68: `PHASE_VALIDATE`; 69: `PHASE_BUILD`; 74: `PHASE_VALIDATE`; 228: `PHASE_PLAN`; 229: `PHASE_PREPLAN` |
| runner/FeatureTaskRuntimeRunnerPolicies.kt | 45: `PHASE_IMPLEMENT`; 46: `PHASE_SIMPLIFY` |
| runner/FeatureTaskRuntimeStatusService.kt | 191: `PHASE_VALIDATE`; 222: `PHASE_BUILD`; 223: `PHASE_VALIDATE` |
| runner/FeatureTaskRuntimeStatusServicePhaseResolution.kt | 29: `PHASE_VALIDATE`; 30: `PHASE_BUILD` |
| validation/FeatureTaskRuntimeBuildGateCoordinator.kt | 319, 346, 357: `PHASE_BUILD` |
| validation/FeatureTaskRuntimeReadinessGateCoordinator.kt | 307, 371: `"commit_push"`; 433: `PHASE_COMMIT_PUSH` |
| validation/FeatureTaskRuntimeValidationGateCoordinator.kt | 66, 70: `"validate"`; 84, 97: `PHASE_VALIDATE` |
| validation/FeatureTaskRuntimeValidationGatePolicy.kt | 61: `PHASE_VALIDATE` |

## After

Recounted on 2026-09-27 with the same scans. This is a static count; the guards run in validate.

### Durable collaborators referenced by the run loop and strategies

Durable type names (`FeatureTaskRuntimePhaseRecorder`, `FeatureTaskRuntimeGoalContinuationRecorder`,
`FeatureTaskPhaseSettlementService`, `AgentActivityStampWriter`, `WorktreeEditJournalWriter`,
`FeatureTaskRuntimeRunInvariantsStore`, `FeatureTaskRuntimeProbeWriters`) and imports of `runloop.durable` under
`runloop/` and `slot/`, outside `runloop/durable/`: 0. Only three files reference these types, all in
`runloop/durable/`: `DurablePhaseRunAdapters.kt`, `FeatureTaskRuntimeRunPreparation.kt`, and
`FeatureTaskRuntimeRunInvariantsStore.kt`.

Some variables keep their old names (`recorder`, `goalContinuationRecorder`, `phaseSettlementService`). They now
hold the port types `PhaseRunRecords`, `PhaseRunGoal`, and `PhaseRunSettlements`, not the durable stores.

Checkpoint git operations (`writeSubtaskCommitPreservingHistory`, `amendHeadCommit`, `updateCheckpointRef`,
`resolveCheckpointRef`, `deleteCheckpointRefsUnderPrefix`) outside `runloop/durable/`: 0. The run loop writes
checkpoints through `PhaseRunCheckpoints`, backed by `DurablePhaseRunCheckpoints`.

`FeatureTaskDurableStoreArchitectureTest` guards both counts.

Audit repair, 2026-09-27: `slot/plan/PlanDecompositionStop.kt` still took `FeatureTaskRuntimeDecomposeTerminalRecorder`
(through `phaseGates`) and wrote the decompose terminal directly. It now reads and writes the terminal through
`PhaseRunRecords.loadDecomposeTerminal` and `recordDecomposeTerminal`, backed by `DurablePhaseRunRecords`. The guard's
name list now also covers `FeatureTaskRuntimeDecomposeTerminalRecorder`, `FeatureTaskPhaseSettlementRepository`,
`FeatureTaskRuntimeWorkflowPersistence`, `WorkflowStateRepository`, `FeatureTaskRuntimeReviewGenerationRecorder`,
`FeatureTaskRuntimeGoalReviewCompletionRecorder`, `SupersededCheckpointPromoter`, `pruneSubtaskCheckpointRefs`,
`listCheckpointRefs`, and `deleteCheckpointRef`. Its synthetic test adds an aliased durable import and an
`updateCheckpointRef` call.

Audit repair, 2026-09-27: `runloop/core/FeatureTaskRuntimeRunLoopBackwardEdge.kt` called
`phaseGates.branchSetupRunner.ensureFeatureBranch`, so the loop reached the branch setup runner's durable
resolved-branch write through a property. It now calls `PhaseRunState.ensureFeatureBranch(guardPhase)`, backed by
`DurablePhaseRunState`. The guard also names `FeatureTaskRuntimeBranchSetupRunner`, `branchSetupRunner`, and
`decomposeTerminalRecorder`, and its synthetic test adds a `phaseGates.branchSetupRunner` reach.

### Durable consumers outside the run loop

These production classes under `featuretask` still depend on durable stores or checkpoint git operations, and each
stays:

- The store adapters themselves: `phase/record/*`, `phase/briefing/FeatureTaskRuntimePhaseBriefingRecorder`,
  `phase/core/FeatureTaskPhaseSettlementService`, `persist/FeatureTaskRuntimeWorkflowPersistence`,
  `lifecycle/continuation/*Recorder` and the artifact patcher, `lifecycle/core` (rejected-output recorder, probe
  writers), `review/core`, `review/finding`, and `review/goal` recorders. They are the durable side the ports delegate to.
- The checkpoint and subtask git owners: `lifecycle/checkpoint` (ref prune, superseded promoter), `lifecycle/subtask`
  (commit preservation, resolver, finalisation), and `lifecycle/remediation` (base reconcilers). The durable entry and
  `DurablePhaseRunCheckpoints` call them.
- `phase/core/FeatureTaskRuntimePhaseGates` and its boundaries: the injected holder the runner and the durable state
  read collaborators from.
- `lifecycle/branch/FeatureTaskRuntimeBranchSetupRunner` and
  `lifecycle/continuation/FeatureTaskRuntimeGoalContinuationOutcomeProjection`: branch setup and the goal outcome write.
  Branch setup runs through `DurablePhaseRunState.ensureFeatureBranch`, and the durable entry writes the goal outcome
  after the loop.
- The durable entry in `runner/`: `FeatureTaskRuntimeRunner` and `FeatureTaskRuntimeRunnerLaunchOutcomes` (crash
  reconciliation, preparation, reconstruction, finish).
- `runner/FeatureTaskRuntimeStatusService`: the read-only status projection pinned in the inbound API. It reads the
  records, run invariants, and decompose terminal directly and never drives the loop.

`prepare/` references none of the guarded names.

### Census items after

| Item | Run-loop access after |
| --- | --- |
| Workflow snapshot and step status | `PhaseRunRecords.recordPhaseState`, `recordCompletedPhase`, `loadPhaseRecords` |
| Phase records and ledger entries | `PhaseRunRecords.appendLedgerEntry`, `appendQuarantineEntry`, `recordIncompleteImplementationAttempt` |
| Run invariants | `FeatureTaskRuntimeRunPreparation` in `runloop/durable/`, called from the durable entry |
| Runtime session rows | `FeatureTaskRuntimeRunPreparation` in `runloop/durable/`, called from the durable entry |
| Checkpoints | `PhaseRunCheckpoints`, and `PhaseRunRecords.appendCheckpointIdentity`, `loadCheckpointIdentities`, `recordWorkflowOwnedPaths` |
| Resume reconstruction | The runner builds `FeatureTaskRuntimeRunState` and hands it to `DurablePhaseRunState.progress` |
| Goal continuation reads | `PhaseRunGoal` |
| Activity stamps, liveness, leases | `PhaseLaunchState.launchObservation`, backed by `DurablePhaseRunState` |
| Lifecycle telemetry | `phaseGates.lifecycleTelemetry` in the runner only (unchanged) |

Resume parity per item:

| Item | Suite |
| --- | --- |
| Workflow snapshot and step status | `FeatureTaskRuntimeRunStateReconstructionTest`, `SlotBaselineFixtureTest` |
| Phase records and ledger entries | `FeatureTaskRuntimeRunStateReconstructionTest`, `FeatureTaskRuntimeReviewFixResumeParityTest` |
| Run invariants | `FeatureTaskRuntimeRunInvariantsResumeParityTest` (added in audit, 2026-09-27) |
| Runtime session rows | `WorkflowIssueKeyPersistenceTest` (reopen heals or rejects the issue key on the same row) |
| Checkpoints | `FeatureTaskRuntimeCheckpointRefPruneTest`, `FeatureTaskRuntimeCheckpointScopeTest` |
| Resume reconstruction | `FeatureTaskRuntimeRunStateReconstructionTest`, `FeatureTaskRuntimeStatelessAuditTest` |
| Goal continuation reads | `FeatureTaskRuntimeGoalContinuationAdoptionPersistenceTest` |
| Activity stamps, liveness, leases | `GoalRunnerExecutionLeaseTest` |
| Lifecycle telemetry | `FeatureTaskRuntimeAgentContextTelemetryTest` |

No test covered the run-invariant freeze through the runner. The new test resumes a workflow whose durable invariants
differ from the request and asserts that the durable row and the audit briefing keep the durable acceptance criteria and
mandates. The bug it catches is a resume that re-freezes the requested invariants. The fixtures are unchanged.

### Slot files referencing `FeatureTaskRuntimeRunLoopContext`

0. The dependency-direction rule rejects a strategy or slot machinery file that names it.

### Step-identity references outside `slot`

0 across the four forms, over every package outside `slot`. The rule has no package list.

Shared code now uses these instead:

- `PhaseSlot` membership (`step in PhaseSlot.X.steps`, `PhaseSlot.X.steps.toSet()`).
- Strategy-owned resume rules. `PhaseStrategyLookup.resumeRules(facts)` maps a step id to the `PhaseResumeRules` of
  the strategy that owns it. Shared code asks those rules whether a step tracks review passes, resumes past
  completion, or retries a persisted block.
- Reported-gate facts. A status-projecting strategy answers `reportedGate(stepId)` with `BUILD` or `VALIDATION`, and
  the status service reads `gateReportedBy` and `stepReporting` instead of naming the validation step.
- The transition declaration: `forwardPhaseIds.first()` for the entry step and the `REVIEW_FIX_LOOP_ID` backward edge
  for the verify step.
- A step id passed in by the calling strategy. The validation and readiness gates take `phaseId` or `stepId`. The
  review-generation writes (`persistReviewGenerationInvalidation`, `advanceReviewGeneration`,
  `reviewedCheckpointFingerprint`) take `reviewStepId` from the `code_review` loop rules.

Audit finding, 2026-09-27: the earlier count was zero only by alias evasion. `PhaseStepRole`, a slot enum with one
constant per step id, stood in for about 57 references in 19 files. Repair, same day: `PhaseStepRole` is deleted, and
each decision now uses one of the routes above. The step-identity scan now reads every non-private `slot` enum, treats
an entry whose constructor arguments include a single step id (a `PHASE_*` constant or a step-id literal, positional
or named) as a step-id alias, and flags any use outside `slot`: qualified, import-aliased, star-imported, or bare
imported. A synthetic test covers each form, plus a non-step enum and a private enum that must stay silent. By
meaning, no file outside `slot` names a step, so the count is 0.
