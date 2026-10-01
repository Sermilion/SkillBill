package skillbill.engine.goalrunner.reset

import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestProjectionPersistence
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.goalrunner.planning.cascadeEligiblePlanSubtaskIds
import skillbill.ports.goalrunner.GoalPlanningPreparationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.workflow.decomposition.afterReplanChildDeletion
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

internal class WorkflowGoalRunnerScopedReplanPersistence(
  private val projectionPersistence: WorkflowGoalRunnerManifestProjectionPersistence,
) {
  fun executeScopedReplan(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): Pair<GoalRunnerScopedReplanWriteResult, DurableWorkflowArtifacts> {
    val preparations = unitOfWork.goalPlanningPreparations
    val plannedBefore = preparations.listPreparedPlanSubtaskIds(state.parentWorkflowId)
    val sharedBefore = preparations.hasPreparedSharedPreplan(state.parentWorkflowId)
    val discard = discardScopedReplanPlans(preparations, state, subtaskId, options, plannedBefore)
    val plannedAfter = preparations.listPreparedPlanSubtaskIds(state.parentWorkflowId)
    val sharedAfter = preparations.hasPreparedSharedPreplan(state.parentWorkflowId)
    val clearedChildIds =
      deleteStaleReplanChildren(
        unitOfWork,
        state,
        listOf(subtaskId) + discard.cascadedIds,
      )
    val projection =
      projectionPersistence.saveInTransaction(
        unitOfWork,
        state.copy(manifest = state.manifest.afterReplanChildDeletion(clearedChildIds)),
        mergeConcurrentProgress = false,
      )
    return GoalRunnerScopedReplanWriteResult(
      state = projection.state,
      deletedPlanCount = discard.deleted,
      plannedSubtaskIdsBefore = plannedBefore,
      plannedSubtaskIdsAfter = plannedAfter,
      sharedPreplanPrepared = sharedAfter,
      sharedPreplanPreparedBefore = sharedBefore,
      discardedSharedPreplan = sharedBefore && !sharedAfter,
      cascadedPlanSubtaskIds = discard.cascadedIds,
      clearedChildSubtaskIds = clearedChildIds,
    ) to projection.projectionArtifacts
  }

  private data class ScopedReplanDiscard(
    val cascadedIds: List<Int>,
    val deleted: Int,
  )

  private fun discardScopedReplanPlans(
    preparations: GoalPlanningPreparationRepository,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
    plannedBefore: List<Int>,
  ): ScopedReplanDiscard {
    if (!options.includeSharedPreplan) {
      return ScopedReplanDiscard(
        cascadedIds = emptyList(),
        deleted = preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId),
      )
    }
    val cascadedIds =
      cascadeEligiblePlanSubtaskIds(
        plannedIds = plannedBefore.filter { it != subtaskId },
        subtasks = state.manifest.subtasks,
      )
    val retainedIds = plannedBefore.filter { it != subtaskId && it !in cascadedIds }
    val expectedDigest = options.expectedSharedPayloadSha256
    val deleted =
      if (expectedDigest != null) {
        val identity =
          requireNotNull(options.planningIdentity) {
            "planningIdentity is required when discarding a shared preplan by digest."
          }
        if (retainedIds.isEmpty()) {
          preparations.deleteSharedPreplan(identity, expectedDigest)
          if (subtaskId in plannedBefore) 1 else 0
        } else {
          preparations.invalidateSharedPreplan(identity, expectedDigest)
          cascadedIds.forEach { id -> preparations.deleteSubtaskPlan(state.parentWorkflowId, id) }
          preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId)
        }
      } else {
        cascadedIds.forEach { id -> preparations.deleteSubtaskPlan(state.parentWorkflowId, id) }
        preparations.deleteSubtaskPlan(state.parentWorkflowId, subtaskId)
      }
    return ScopedReplanDiscard(cascadedIds = cascadedIds, deleted = deleted)
  }
}

internal fun deleteStaleReplanChildren(
  unitOfWork: UnitOfWork,
  state: GoalRunnerManifestState,
  subtaskIds: List<Int>,
): List<Int> =
  subtaskIds.distinct().sorted().filter { id ->
    val subtask = state.manifest.subtasks.singleOrNull { it.id == id }
    val childWorkflowId = subtask?.workflowId?.takeIf(String::isNotBlank)
    if (
      subtask == null ||
      childWorkflowId == null ||
      subtask.status.decompositionStatus() in setOf(DecompositionStatus.COMPLETE, DecompositionStatus.SKIPPED)
    ) {
      false
    } else {
      unitOfWork.workflowStates.deleteGoalChildWorkflow(
        state.parentWorkflowId,
        id,
        childWorkflowId,
        GoalChildWorkflowDeletionScope.TERMINAL_OR_RESUMABLE,
      ) == 1
    }
  }
