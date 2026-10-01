# SKILL-397 Subtask 2 - domain-owned-aggregate-transitions

Parent spec: [.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec.md](./spec.md)
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

## Next Path

skill-bill goal SKILL-397

## Spec Path

.feature-specs/SKILL-397-runtime-domain-boundary-integrity/spec_subtask_2_domain-owned-aggregate-transitions.md
