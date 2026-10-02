# SKILL-397 Subtask 2 - domain-owned-aggregate-transitions

Parent spec: [.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec.md](spec.md)
Issue key: SKILL-397

## Scope

(F-007) Move the pure DecompositionManifest, DecompositionSubtask, GoalRunnerControlState and GoalRunnerStopReason transitions into runtime-domain. From engine: GoalRunnerBranchPlan.kt (withAttemptedSubtask, withWorkflowId, knownWorkflowId, withCompletedSubtask, withStoppedSubtask, withResumableSubtask); GoalRunnerBranchPlanSubtaskOrdering.kt (withValidationQualityRetrySubtask, withBranchSetupBlockedSubtask, withBlockedSelection, branchForFinalPullRequest); goalrunner/manifest/GoalRunnerManifestSnapshotProjection.kt (isAtUnlaunchedBoundary, resetManifest, restartIntent, replanIntent, plus deleting the 8 restated NO_CURRENT_SUBTASK_* and SUBTASK_* consts); goalrunner/reset/WorkflowGoalRunnerScopedReplanPersistence.kt (afterIncompatibleChildDeletion, afterReplanChildDeletion); GoalRunnerReAttemptCause.kt (GoalRunnerStopReason.toLedgerAction, toDiagnosticClass, nextSafeAction); GoalRunnerWorkflowFamilyLookup.kt (pauseAtOperatorBoundary); GoalRunnerControlCoordinator.kt (targetReached, taking DecompositionManifest instead of ports GoalRunnerManifestState). From application: DecompositionWorkflowResumeAlignment.kt (withStartedSubtask, withCommittedSubtask, branchForSubtask, baseForSubtask) and DecompositionManifestRuntimeState.kt (withPreservedRuntimeState). Manifest transitions go to skillbill.workflow.decomposition; goal-runner state and stop-reason rules go beside their enums in goalrunner.model. Add DecompositionSubtaskAction (none, start, resume, blocked, complete) with wireValue and fromWire beside DecompositionStatus in workflow/model/ClosedStatusTypes.kt. Every transition in domain, engine and application writes status and action tokens through DecompositionStatus or DecompositionSubtaskAction `.wireValue`, including the existing domain DecompositionManifestTransitions.kt literals. The subtask reset (status pending; branch, commit, workflow id, blocked reason and last resumable step cleared) exists as one domain function used by resetManifest and both replan deletions. Callers keep their names and behaviour. Engine keeps the data class GoalRunnerBranchPlan, branchPlanFor, toPullRequestRequest and toResetSnapshot. Application keeps withRuntimeUpdate, currentSubtaskIdForUpdate, withRuntimeFields, statusFromUpdate and assertExecutionModelCanReplace.

## Acceptance Criteria

1. Every function named in the scope as moving is declared in runtime-domain main and nowhere in runtime-engine or runtime-application main.
2. DecompositionSubtaskAction exists in ClosedStatusTypes.kt with wireValue and fromWire. GoalRunnerManifestSnapshotProjection.kt declares none of the NO_CURRENT_SUBTASK_* or SUBTASK_* constants.
3. No main source in runtime-domain, runtime-engine or runtime-application passes a string literal as a CurrentSubtaskIntent action, or copies a DecompositionManifest or DecompositionSubtask with a string-literal status.
4. The subtask reset field set is written in exactly one domain function, and resetManifest, afterIncompatibleChildDeletion and afterReplanChildDeletion use it.
5. targetReached takes a DecompositionManifest and imports no runtime-ports type.
6. toPullRequestRequest, branchPlanFor, toResetSnapshot, withRuntimeUpdate, currentSubtaskIdForUpdate, withRuntimeFields and assertExecutionModelCanReplace remain in their current modules.
7. runtime-domain tests cover withCompletedSubtask parent-status derivation, resetManifest in both hard and soft modes, restartIntent dependency-aware selection, isAtUnlaunchedBoundary, and pauseAtOperatorBoundary.
8. The runtime-domain package cycle baseline gains no row, and no architecture exemption is added.

## Non-Goals

- Enum-typing the DecompositionManifest, DecompositionSubtask or CurrentSubtaskIntent fields.
- Moving Path-bearing or ports-dependent helpers into domain.
- Unifying the add-on selection decoders (F-008).
- Replacing `require` error reporting in domain.

## Dependency Notes

Depends on: none
Independent of subtask 1. Touches engine goalrunner files that SKILL-390 may move, and application decomposition files that SKILL-388 edits. Whichever lands second applies the rule that pure aggregate transitions live in domain to the files present.

## Validation Strategy

Goal gates: build, unit tests and the repoTest architecture suite. Test obligations: the transition tests listed in the acceptance criteria. Existing engine and application goal-runner and decomposition tests pass unchanged apart from import updates.

## Implementation Details

Census taken on `base/SKILL-380-phase-slot-strategies` at `c038e02e5`. SKILL-390 has not landed, so the engine `goalrunner` files are still where the scope names them. SKILL-388 already moved the application transitions into `application/workflow/decomposition/DecompositionWorkflowResumeAlignment.kt:218-259` and `application/decomposition/DecompositionManifestRuntimeState.kt:75`. Paths below are relative to `../../../runtime-kotlin`. Domain paths are relative to `runtime-domain/src/main/kotlin/skillbill/`.

Every body moves verbatim. The only edits to a body are swapping status and action literals for enum `.wireValue` and calling the shared reset function. Each function keeps its name, receiver, parameters, defaults and result. Moved functions become public in domain because engine and application call them across a module boundary. This applies to the ones that were `internal` (`afterIncompatibleChildDeletion`, `afterReplanChildDeletion`, `targetReached`, and the five application functions) as well as the rest.

### Task 1: Add DecompositionSubtaskAction (AC-002, AC-003)

- In `workflow/model/ClosedStatusTypes.kt`, add `enum class DecompositionSubtaskAction(val wireValue: String) { NONE("none"), START("start"), RESUME("resume"), BLOCKED("blocked"), COMPLETE("complete") }` directly after `DecompositionStatus`.
- Its companion is `fun fromWire(value: String): DecompositionSubtaskAction? = entries.firstOrNull { it.wireValue == value }`, the same shape as `WorkflowStatus.fromWire`.
- Add no alias mapping, no `String?` extension helper, no typealias and no constant.
- `fromWire` gets real callers in Tasks 3 and 6, where action reads compare through it.

### Task 2: Move the manifest and subtask transitions into skillbill.workflow.decomposition (AC-001, AC-003, AC-004)

**Existing domain file: `workflow/decomposition/DecompositionManifestTransitions.kt`**
- Rewrite the 6 existing literal actions as `DecompositionSubtaskAction.X.wireValue`: the 4 in `intentFor`, plus the ones in `withBlockedSubtask` and `withRetriedSubtask`.
- Append these subtask write transitions, moved verbatim:
  - from engine `engine/goalrunner/execution/support/GoalRunnerBranchPlan.kt`: `withAttemptedSubtask`, `withWorkflowId`, `knownWorkflowId`, `withCompletedSubtask`, `withStoppedSubtask`, `withResumableSubtask`;
  - from engine `GoalRunnerBranchPlanSubtaskOrdering.kt`: `withValidationQualityRetrySubtask`, `withBranchSetupBlockedSubtask`, `withBlockedSelection`, `branchForFinalPullRequest`;
  - from application `DecompositionWorkflowResumeAlignment.kt`: `withStartedSubtask`, `withCommittedSubtask`, `branchForSubtask`, `baseForSubtask`;
  - from application `DecompositionManifestRuntimeState.kt`: `withPreservedRuntimeState`.
- In every moved body, replace each `status = "…"` with `DecompositionStatus.X.wireValue` and each `action = "…"` with `DecompositionSubtaskAction.X.wireValue`.
- `branchForSubtask`, `baseForSubtask` and `branchForFinalPullRequest` read `executionModel`, `baseBranch`, `featureBranch` and `stackBranches`. These are `DecompositionManifest` member properties, so the moved bodies compile without the application `baseBranch(...)` / `executionModel(...)` function imports.
- `knownWorkflowId`, `withCompletedSubtask`, `withStoppedSubtask` and `withResumableSubtask` take `skillbill.goalrunner.model.GoalRunnerReconciledOutcome`. This adds one package edge, `workflow.decomposition` → `goalrunner.model`.
  - The edge is acyclic today. In domain main, only `decomposition.runtime` imports the root `workflow.decomposition` package, and only `workflow.engine.model` imports `decomposition.runtime`. The transitive import closure of `goalrunner.model` reaches none of the importers of `workflow.engine.model`, which are `goalrunner`, `workflow.engine`, `verify`, `taskruntime.artifact`, `phase.task`, `persistence.task.runtime.goal` and `decomposition.runtime`. That closure is: `workflow.model`, `workflow.time`, `persistence.artifact`, `decomposition.model`, `goalreview`, `repair.task`, `repair`, `taskruntime.model.validation`, `taskruntime.model.feature`, `persistence.task.runtime.store`, `taskruntime.model.core`, `review.model`, `review.context` and its model packages, `idestatus.model`, `agent.model`, `skillbill.model`, contracts and error. So `goalrunner.model` cannot reach `workflow.decomposition`.
  - Task 4 must add no import of `workflow.decomposition` to `goalrunner.model`.

**New domain file: `workflow/decomposition/DecompositionManifestRestart.kt`**
- Move these from engine `engine/goalrunner/manifest/GoalRunnerManifestSnapshotProjection.kt`: `isAtUnlaunchedBoundary`, `resetManifest`, `restartIntent`, `replanIntent`.
- Move these from engine `engine/goalrunner/reset/WorkflowGoalRunnerScopedReplanPersistence.kt`: `afterIncompatibleChildDeletion`, `afterReplanChildDeletion`.
- Add the single reset function (AC-004): `internal fun DecompositionSubtask.resetToPending(): DecompositionSubtask = copy(status = DecompositionStatus.PENDING.wireValue, branch = null, commitSha = null, workflowId = null, blockedReason = null, lastResumableStep = null)`.
  - `resetManifest` uses it in two places: its `hard` branch and its fallback `else` branch, which replace the `freshReset` lambda.
  - `afterIncompatibleChildDeletion` and `afterReplanChildDeletion` map their target ids through it.
  - Every caller lives in domain, so `internal` is enough.
- `isAtUnlaunchedBoundary`:
  - The unselected check becomes `currentSubtaskIntent.subtaskId == 0 && DecompositionSubtaskAction.fromWire(currentSubtaskIntent.action) == DecompositionSubtaskAction.NONE`.
  - The selected-but-unlaunched check compares `fromWire(action) == DecompositionSubtaskAction.START`.
  - Literal `0` matches every other "no current subtask" site, such as `intentFor` and `restartIntent`.
- `restartIntent` and `replanIntent` build their actions from `DecompositionSubtaskAction` (`COMPLETE`, `RESUME`, `START`). The `if (nextRunnable == null) "complete" else "start"` expression also takes enum wire values.
- `resetManifest` writes its soft-mode `status = "in_progress"` as `DecompositionStatus.IN_PROGRESS.wireValue`.
- Keep the hard and soft semantics from SKILL-346 exactly:
  - hard resets every subtask;
  - soft keeps COMPLETE and SKIPPED subtasks but clears their blocked reason and last step;
  - soft resumes launched subtasks (those with a workflowId) as `in_progress`, clearing the blocked reason;
  - soft resets everything else;
  - both modes then set `restartIntent` and `withParentStatus()`.
- This file is a separate file so `DecompositionManifestTransitions.kt` stays a per-subtask write file, not a 400-line grab bag. The root package grows from 3 to 4 files. The logical-type lines billed to `DecompositionManifest` stay well under 1200: models 94 + transitions ~330 + restart ~120 + selector 116.

### Task 3: Strip the engine and application originals and repoint callers (AC-001, AC-002, AC-006)

**`engine/goalrunner/execution/support/GoalRunnerBranchPlan.kt` and `GoalRunnerBranchPlanSubtaskOrdering.kt`**
- Delete the six moved functions from `GoalRunnerBranchPlan.kt`.
- Delete the four moved functions from the ordering file.
- Move what is left of the ordering file into `GoalRunnerBranchPlan.kt`, beside the data class `GoalRunnerBranchPlan`: `toPullRequestRequest`, private `toPullRequestTitle`, and `internal branchPlanFor`.
- Then delete `GoalRunnerBranchPlanSubtaskOrdering.kt`, because its name no longer fits what it holds (SKILL-361 nesting rule: no empty or misnamed files).
- `toPullRequestRequest` now imports `skillbill.workflow.decomposition.branchForFinalPullRequest`.
- Do not carry over the ordering file's `skillbill.application.decomposition.baseBranch` and `executionModel` imports. Those functions take a `DecompositionPlanningResult`. Inside a `DecompositionManifest` receiver, `baseBranch` and `executionModel` resolve to the member properties, so the imports are dead. Keep the `GoalPullRequestRequest`, `DecompositionExecutionModel` and `java.nio.file.Path` imports. Drop `CurrentSubtaskIntent`, `GoalRunnerReconciledOutcome`, `DecompositionStatus` and `decompositionStatus` once nothing in the merged file uses them.
- No guard or inventory names either file. Checked with grep over `runtime-core/src`, ARCHITECTURE.md and the engine and application tests.

**`engine/goalrunner/manifest/GoalRunnerManifestSnapshotProjection.kt`**
- Delete the 8 consts (`NO_CURRENT_SUBTASK_ID`, `NO_CURRENT_SUBTASK_ACTION`, `SUBTASK_ACTION_START`, `SUBTASK_ACTION_RESUME`, `SUBTASK_STATUS_*`) and the 4 moved functions.
- Keep `toResetSnapshot`, `toStatusMap` and `toAcceptedSubtasks`.
- Drop the imports that become unused.

**`engine/goalrunner/reset/WorkflowGoalRunnerScopedReplanPersistence.kt`**
- Delete the two moved functions.
- Keep `deleteStaleReplanChildren` and the class. Per the scoped-replan decision `#62f980f9394c`, the engine-side side effects stay in engine.
- Import `skillbill.workflow.decomposition.afterReplanChildDeletion`.

**`engine/goalrunner/reset/GoalRunnerHardResetOrphanTrap.kt:5,68`**
- Replace the `SUBTASK_ACTION_RESUME` import and comparison with `DecompositionSubtaskAction.fromWire(manifest.currentSubtaskIntent.action) == DecompositionSubtaskAction.RESUME`.

**Application**
- Delete the four functions at `DecompositionWorkflowResumeAlignment.kt:218-259`. The rest of the file stays.
- Delete `withPreservedRuntimeState` from `DecompositionManifestRuntimeState.kt`.
- `assertExecutionModelCanReplace`, `withRuntimeUpdate`, `manifestPathFromArtifacts` and the other functions stay put. `currentSubtaskIdForUpdate`, `withRuntimeFields` and `statusFromUpdate` are not touched (AC-006).
- Remove imports left unused: `CurrentSubtaskIntent`, `DecompositionExecutionModel`, `DecompositionStatus`, `DecompositionSubtask`, and the application `baseBranch`/`executionModel` function imports where nothing else references them.

**Callers to repoint to `skillbill.workflow.decomposition.*`**
- Engine:
  - `engine/goalrunner/launch/GoalRunnerSubtaskLaunchPrepare.kt` (`withAttemptedSubtask`, `withBranchSetupBlockedSubtask`, `withWorkflowId`);
  - `engine/goalrunner/reset/GoalRunnerResetReplanCoordinator.kt` (`replanIntent`, `resetManifest`);
  - `engine/goalrunner/reset/GoalRunnerPurgeCoordinator.kt` (`resetManifest`);
  - `engine/goalrunner/manifest/WorkflowGoalRunnerManifestStore.kt` (`afterIncompatibleChildDeletion`);
  - `engine/goalrunner/status/GoalRunnerStatusControlVerbs.kt` (`isAtUnlaunchedBoundary`);
  - `engine/goalrunner/execution/core/GoalRunnerIterationOutcome.kt` (`knownWorkflowId`, `withCompletedSubtask`, `withResumableSubtask`, `withStoppedSubtask`, `withValidationQualityRetrySubtask`);
  - `engine/goalrunner/execution/core/GoalRunnerGoalLoop.kt` (`withBlockedSelection`).
- Application, which had same-package callers and so needs new imports:
  - `application/workflow/decomposition/DecompositionWorkflowContinuation.kt:375` (`withStartedSubtask`);
  - `DecompositionWorkflowContinuationAdvancement.kt:75-90` (`branchForSubtask`, `baseForSubtask`, `withCommittedSubtask`);
  - `application/decomposition/DecompositionManifestWriter.kt:140,183` (`withPreservedRuntimeState`).
- Before handing off, grep each moved name again across `runtime-engine/src/main` and `runtime-application/src/main`, because same-package callers have no import line to find.
  - The plan-time census found no other caller.
  - Some other hits are only properties or parameters that share a name. Leave them alone:
    - `nextSafeAction` in `GoalRunnerLedgerRecorder`, `GoalRunnerLaunchModels`, `GoalRunnerStoreModels`, `GoalRunnerLoopModels` and `GoalRunnerFinalization`;
    - `knownWorkflowId` in `GoalRunnerStopReports` and `GoalRunnerLoopModels`;
    - the local `val targetReached` at `GoalRunnerControlCoordinator.kt:176`.

**Tests: import updates only**
- Repoint `import skillbill.engine.goalrunner.execution.support.withWorkflowId` to `skillbill.workflow.decomposition.withWorkflowId` in six engine test files: `GoalRunnerTest.kt:32`, `status/GoalRunnerStatusProjectionDegradationTest.kt:13`, `execution/core/GoalModeAttributionUnitTest.kt:15`, `telemetry/GoalRunnerTelemetryTest.kt:18`, `persist/GoalRunnerDirectRuntimeContinuationTest.kt:10` and `persist/GoalRunnerLedgerTest.kt:10`.
- `GoalRunnerTest.kt` declares its own `internal withWorkflowId` (identical body) and a `private withCompletedSubtask(subtaskId, workflowId, commitSha)` at :5089 and :5120.
  - Today the explicit import of `withWorkflowId` already wins over the local declaration, so repointing that import keeps behaviour.
  - Do not add an import of the domain `withCompletedSubtask` there, and do not touch either local helper.
- No test asserts on `toLedgerAction`, `toDiagnosticClass` or `nextSafeAction` directly. `GoalRunnerLedgerTest` reads only the `nextSafeAction` property.

### Task 4: Move the control-state and stop-reason rules into skillbill.goalrunner.model (AC-001, AC-005)

**`goalrunner/model/GoalRunnerControlModels.kt`**
- Add, verbatim, `GoalRunnerControlState.pauseAtOperatorBoundary(pausedAtNow, targetReached = false)` from `engine/goalrunner/execution/support/GoalRunnerWorkflowFamilyLookup.kt:34`.
- Add `fun GoalRunnerControlState.targetReached(manifest: DecompositionManifest): Boolean = stopAfterSubtaskId?.let { targetId -> manifest.subtasks.any { it.id == targetId && it.status.decompositionStatus() == DecompositionStatus.COMPLETE } } == true && !stopAfterConsumed`.
  - It takes the manifest directly instead of the engine `GoalRunnerManifestState`.
  - It needs only `DecompositionManifest`, `DecompositionStatus` and `decompositionStatus`, which `goalrunner.model` already imports. It imports no ports type.

**`goalrunner/model/GoalRunnerTerminalModels.kt`**
- Add, verbatim, `GoalRunnerStopReason.toLedgerAction()`, `toDiagnosticClass()` and `nextSafeAction()` beside the enum, from `engine/goalrunner/execution/support/GoalRunnerReAttemptCause.kt:39-90`. `GoalAttemptLedgerAction` is in the same package.

**Engine side**
- Delete these functions from `GoalRunnerWorkflowFamilyLookup.kt` (it keeps `workflowFamilyFor` and the add-on decoder) and from `GoalRunnerReAttemptCause.kt` (it keeps `reAttemptCauseFor`, `causingLoopEntryFor`, `loopReAttemptPriority`, `confirmedAliveKillDiagnosticClass` and `recoverySafeAction`).
- Delete `targetReached` from `engine/goalrunner/status/GoalRunnerControlCoordinator.kt:228`.
- Change its caller at :152 to `controls.targetReached(authoritativeManifest)`.
- Import `skillbill.goalrunner.model.pauseAtOperatorBoundary` and `skillbill.goalrunner.model.targetReached`.
- Repoint `GoalRunnerIterationOutcome.kt` imports of `nextSafeAction`, `toDiagnosticClass` and `toLedgerAction` to `skillbill.goalrunner.model.*`.
- Drop imports left unused, for example the `GOAL_PAUSE_REASON_*` and `GoalAttemptLedgerAction` imports in the engine files.

### Task 5: Rewrite literals outside the move list (AC-003)

- `engine/goalrunner/manifest/GoalRunnerManifestReconciliation.kt:163-170`: replace the four `CurrentSubtaskIntent` actions in `withDerivedCurrentIntent` with `DecompositionSubtaskAction.{BLOCKED,RESUME,START,COMPLETE}.wireValue`.
- `engine/goalrunner/execution/core/GoalRunnerGoalLoop.kt:233-234`: set `status = DecompositionStatus.BLOCKED.wireValue` and use action `DecompositionSubtaskAction.BLOCKED.wireValue`.
- `application/decomposition/DecompositionManifestWriter.kt:251`: use action `DecompositionSubtaskAction.START.wireValue`.
- `goalrunner/GoalRunnerPolicy.kt:150`: this builds a new `DecompositionSubtask`, not a copy. Rewrite it to `DecompositionStatus.PENDING.wireValue` anyway, because the scope says every transition writes through the enums.
- Leave `engine/featuretask/phase/core/FeatureTaskPhaseSettlementService.kt:56` alone. Its `status = "blocked"` is a `NormalizedFeatureTaskRuntimePhaseOutput` phase status, not a manifest or subtask status.
- Final check: `grep -rnE '(action|status) = "(none|start|resume|blocked|complete|pending|in_progress|skipped)"'` and `grep -rnE 'CurrentSubtaskIntent\([^)]*"|action = if'` over the three modules' `src/main` must match only the phase-output site above.

### Task 6: Add the domain transition tests (AC-007)

Each test catches one realistic regression. None mirrors an implementation line.

**`runtime-domain/src/test/kotlin/skillbill/workflow/decomposition/DecompositionManifestTransitionsTest.kt` (extend; reuse its `manifest(...)` / `subtask(...)` helpers)**
1. `withCompletedSubtask` derives the parent status.
   - Completing the last non-terminal subtask while its sibling is SKIPPED gives manifest `complete` and intent `(0, complete)`.
   - Completing it while a sibling is still pending gives `in_progress`.
   - Bug caught: a goal marked complete while work remains, or never marked complete.
2. `resetManifest(hard = true)` returns every subtask, COMPLETE included, to pending with branch, commit, workflow id, blocked reason and last step all null. The intent becomes `start` on the first runnable subtask.
   - Bug caught: hard reset leaving stale commit or workflow ids, which would make the orphan trap or a resume target a deleted child.
3. `resetManifest(hard = false)`:
   - keeps a COMPLETE subtask complete, with its commit sha, and clears its blocked reason and last step;
   - keeps a launched blocked subtask (with a workflowId) as `in_progress` with its workflow id, and the intent is `resume` on it;
   - resets an unlaunched blocked subtask to pending.
   - Bug caught: soft reset discarding completed work or orphaning a live child.
4. `restartIntent` picks the first pending subtask whose dependencies are complete, skipped, or optional-and-skipped. Example: subtask 1 is pending and depends on pending subtask 2, while subtask 2 has no dependencies, so the intent is `(2, start)`.
   - Bug caught: launching a subtask before its dependency.
5. `isAtUnlaunchedBoundary`:
   - true for intent `(0, none)`;
   - true for a selected pending subtask with no workflow id and action `start`;
   - false once any `in_progress` subtask has a workflow id, even if the intent says `start`.
   - Bug caught: pause or stop treating a running child as unlaunched.

**New `runtime-domain/src/test/kotlin/skillbill/goalrunner/model/GoalRunnerControlStateTest.kt` (the test package already exists and matches the main package)**

6. `pauseAtOperatorBoundary`:
   - a pending operator request becomes `paused` with `operator_request`, `pauseConsumed = true` and the given `pausedAt`;
   - `targetReached = true` with no request becomes `paused` with `stop_after_subtask` and `stopAfterConsumed = true`;
   - an already-paused state keeps its reason and only folds in `stopAfterConsumed`;
   - with no request and no target it returns the state unchanged.
   - Bug caught: a stop-after target that never pauses, or an operator reason overwritten on re-pause.

Not tested, deliberately: `withWorkflowId`, `withCommittedSubtask`, `branchForSubtask`, `baseForSubtask`, `branchForFinalPullRequest`, `knownWorkflowId`, the blocked and retry variants, `afterReplanChildDeletion`, `afterIncompatibleChildDeletion`, `replanIntent`, `targetReached`, and the stop-reason `when` tables. They are one-line field copies or exhaustive `when` tables that the compiler checks. The existing engine and application suites (GoalRunnerTest, the reset, replan and purge tests, and the decomposition continuation tests) already cover them through their callers, unchanged. The `resetToPending` sharing is covered by tests 2 and 3.

### Constraints

- Add no typealias, no new constant, no architecture exemption and no baseline row. The only additions are `DecompositionSubtaskAction`, `resetToPending`, the new restart file, and the new test file (AC-008).
- Do not enum-type the String `status`/`action` fields (non-goal). Do not move Path-bearing or ports-bearing helpers: `toPullRequestRequest`, `branchPlanFor`, `toResetSnapshot`, `deleteStaleReplanChildren`, `workflowFamilyFor`, and the application runtime-update functions stay (AC-006).
- Domain files must not import `java.nio` or `skillbill.ports`. The moved bodies need neither.
- Keep `package` and `import` lines at column 0 (scanner decision `#a84b4b35b741`).
- Wire bytes stay identical, because every enum `wireValue` equals the literal it replaces. Edit no wire fixture or expected-payload assertion.
- Package-cycle risk: if the validate-phase EXACT_PACKAGE_SCC scan reports `workflow.decomposition` or `goalrunner.model` in an SCC, trace the closing edge and break it. Never extend `runtime-domain-package-cycle-baseline.txt`.
- This phase does not build, test or run check. Compile, unit and repoTest proof belong to the build and validate phases. Run Spotless in a plain clone, with `--no-configuration-cache` if it reports a stale cache.

## Next Path

skill-bill goal SKILL-397

## Spec Path

.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec_subtask_2_domain-owned-aggregate-transitions.md
