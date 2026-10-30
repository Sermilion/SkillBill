package skillbill.engine.goalrunner.reset

import skillbill.engine.goalrunner.manifest.SavedManifestProjection
import skillbill.engine.goalrunner.manifest.WorkflowGoalRunnerManifestProjectionPersistence
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.quality.WorkflowGoalRunnerQualityExecutionStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.unfinishedSubtaskIds
import skillbill.workflow.decomposition.withOperatorCompletedSubtasks

internal class GoalRunnerOperatorCompletion(
  private val qualityExecution: WorkflowGoalRunnerQualityExecutionStore,
  private val projectionPersistence: WorkflowGoalRunnerManifestProjectionPersistence,
) {
  fun saveInTransaction(
    unit: UnitOfWork,
    state: GoalRunnerManifestState,
    commitSha: String,
    reason: String,
    completedAt: String,
  ): SavedManifestProjection {
    val parentWorkflowId = state.parentWorkflowId
    val manifest =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)?.decompositionRuntime()
        ?: error("Goal parent '$parentWorkflowId' has no durable manifest.")
    val unfinished = manifest.unfinishedSubtaskIds()
    unfinished.forEach { subtaskId ->
      unit.goalRunnerControls.persistOutOfBandAcceptance(
        parentWorkflowId,
        GoalRunnerOutOfBandAcceptance(subtaskId, commitSha, reason, completedAt),
      )
    }
    qualityExecution.retireForSelectionChange(unit, parentWorkflowId)
    val saved =
      projectionPersistence.saveInTransaction(
        unit,
        state.copy(manifest = manifest.withOperatorCompletedSubtasks(unfinished.toSet(), commitSha)),
        mergeConcurrentProgress = false,
      )
    val controlState =
      unit.goalRunnerControls.persistControlState(
        parentWorkflowId,
        unit.goalRunnerControls.controlState(parentWorkflowId).copy(
          pauseRequested = false,
          pauseConsumed = false,
          paused = false,
          pauseReason = null,
          pausedAt = null,
          goalCompletedAt = completedAt,
          goalPullRequestUrl = null,
          goalBlockedReason = null,
        ),
      )
    return saved.copy(state = saved.state.copy(controlState = controlState))
  }
}
