package skillbill.engine.goalrunner.reset

import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.engine.goalrunner.history.GoalChildHistoryEvidenceStore
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestProjectionPersistence
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanWriteResult
import skillbill.engine.goalrunner.persist.quality.WorkflowGoalRunnerQualityExecutionStore
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.afterReplanChildDeletion
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle

internal class WorkflowGoalRunnerScopedReplanPersistence(
  private val projectionPersistence: WorkflowGoalRunnerManifestProjectionPersistence,
  private val historyEvidence: GoalChildHistoryEvidenceStore,
  private val qualityExecution: WorkflowGoalRunnerQualityExecutionStore,
) {
  private val engine = WorkflowEngine()

  fun executeScopedReplan(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): Pair<GoalRunnerScopedReplanWriteResult, DurableWorkflowArtifacts> {
    qualityExecution.retireForSelectionChange(unitOfWork, state.parentWorkflowId)
    val discard = discardPlannedDetails(unitOfWork, state, subtaskId, options)
    val clearedChildIds = deleteStaleReplanChildren(unitOfWork, state, listOf(subtaskId), historyEvidence)
    val projection =
      projectionPersistence.saveInTransaction(
        unitOfWork,
        state.copy(manifest = state.manifest.afterReplanChildDeletion(clearedChildIds)),
        mergeConcurrentProgress = false,
      )
    return GoalRunnerScopedReplanWriteResult(
      state = projection.state,
      deletedPlanCount = if (subtaskId in discard.plannedBefore) 1 else 0,
      plannedSubtaskIdsBefore = discard.plannedBefore,
      plannedSubtaskIdsAfter = discard.plannedAfter,
      sharedPreplanPrepared = discard.bundlePresent,
      clearedChildSubtaskIds = clearedChildIds,
    ) to projection.projectionArtifacts
  }

  fun clearReplannedChildren(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    subtaskIds: List<Int>,
  ): Pair<GoalRunnerManifestState, DurableWorkflowArtifacts?> {
    val clearedChildIds = deleteStaleReplanChildren(unitOfWork, state, subtaskIds, historyEvidence)
    if (clearedChildIds.isEmpty()) return state to null
    qualityExecution.retireForSelectionChange(unitOfWork, state.parentWorkflowId)
    val projection =
      projectionPersistence.saveInTransaction(
        unitOfWork,
        state.copy(manifest = state.manifest.afterReplanChildDeletion(clearedChildIds)),
        mergeConcurrentProgress = false,
      )
    return projection.state to projection.projectionArtifacts
  }

  private data class PlannedDetailsDiscard(
    val bundlePresent: Boolean,
    val plannedBefore: List<Int>,
    val plannedAfter: List<Int>,
  )

  private fun discardPlannedDetails(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    subtaskId: Int,
    options: GoalRunnerScopedReplanOptions,
  ): PlannedDetailsDiscard {
    val planWorkflowId =
      unitOfWork.workflowStates.findCompletedPlanWorkflowId(state.manifest.issueKey, options.repositoryIdentity)
        ?: return NO_BUNDLE
    val record = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planWorkflowId) ?: return NO_BUNDLE
    val bundle =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(record.artifacts)
        ?.let(::decodePlanBundleFromArtifact)
        ?: return NO_BUNDLE
    val cleared = bundle.withoutPlannedUnits(setOf(subtaskId))
    if (cleared != bundle) {
      val bundleEntry =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.entry(cleared.asWorkflowArtifactEntry())
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          record,
          WorkflowUpdateInput(
            workflowStatus = record.workflowStatus,
            currentStepId = record.currentStepId,
            stepUpdates = null,
            artifactsPatch = WorkflowArtifactPatch.from(mapOf(bundleEntry)),
            sessionId = record.sessionId.orEmpty(),
            acceptedSnapshotMutation = true,
          ),
        )
      unitOfWork.workflowStates.saveAcceptedSnapshotMutation(WorkflowFamily.TASK_RUNTIME, updated)
    }
    return PlannedDetailsDiscard(
      bundlePresent = true,
      plannedBefore = bundle.plannedSubtaskIds(),
      plannedAfter = cleared.plannedSubtaskIds(),
    )
  }

  private fun FeatureTaskRuntimePlanBundle.plannedSubtaskIds(): List<Int> =
    subtasks.filter { it.planned }.map { it.id }.sorted()

  private companion object {
    val NO_BUNDLE =
      PlannedDetailsDiscard(bundlePresent = false, plannedBefore = emptyList(), plannedAfter = emptyList())
  }
}

internal fun deleteStaleReplanChildren(
  unitOfWork: UnitOfWork,
  state: GoalRunnerManifestState,
  subtaskIds: List<Int>,
  historyEvidence: GoalChildHistoryEvidenceStore,
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
      val durableSubtask =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
          ?.decompositionRuntime()?.subtasks?.singleOrNull { it.id == id }
      if (durableSubtask?.workflowId != childWorkflowId) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Durable parent no longer assigns child '$childWorkflowId' to subtask '$id'.",
        )
      }
      val child = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childWorkflowId)
      if (child != null &&
        child.workflowStatus !in GoalChildWorkflowDeletionScope.TERMINAL_OR_RESUMABLE.deletableStatuses
      ) {
        return@filter false
      }
      historyEvidence.capture(unitOfWork, state.parentWorkflowId, id, childWorkflowId)
      val deleted =
        unitOfWork.workflowStates.deleteGoalChildWorkflow(
          state.parentWorkflowId,
          id,
          childWorkflowId,
          GoalChildWorkflowDeletionScope.TERMINAL_OR_RESUMABLE,
        )
      if (deleted != 1) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
          "Child workflow '$childWorkflowId' could not be deleted with its preserved evidence.",
        )
      }
      historyEvidence.retire(unitOfWork, state.parentWorkflowId, id)
      true
    }
  }
