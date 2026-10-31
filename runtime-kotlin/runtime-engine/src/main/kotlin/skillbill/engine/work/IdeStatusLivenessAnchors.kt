package skillbill.engine.work

import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItem
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.WorkflowStatus
import java.time.Instant

internal class IdeStatusLivenessAnchors(
  private val unitOfWork: UnitOfWork,
) {
  fun lifecycle(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): IdeStatusLifecycleState? =
    if (family == IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME &&
      item.currentState == WorkflowStatus.PENDING.wireValue &&
      unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(item.workflowId) == null
    ) {
      IdeStatusLifecycleState.PAUSED
    } else {
      IdeStatusSelectionPolicy.lifecycleFromDurableStateWire(family, item.currentState)
    }

  fun authoritativeUpdatedAt(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): Instant? {
    if (family == IdeStatusWorkflowFamily.FEATURE_GOAL) {
      val durableLifecycle =
        IdeStatusSelectionPolicy.lifecycleFromDurableStateWire(family, item.currentState)
      if (durableLifecycle == IdeStatusLifecycleState.TERMINAL ||
        durableLifecycle == IdeStatusLifecycleState.CANCELED ||
        durableLifecycle == IdeStatusLifecycleState.FAILED
      ) {
        return item.stateEnteredAt
      }
      return listOfNotNull(
        item.stateEnteredAt,
        latestParentGoalChildUpdatedAt(item),
        goalLeaseHeartbeatAt(item),
      ).maxOrNull()
    }

    val fromWorkflow =
      when (family) {
        IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, item.workflowId)
        IdeStatusWorkflowFamily.FEATURE_VERIFY ->
          unitOfWork.workflowStates.get(WorkflowFamily.VERIFY, item.workflowId)
        IdeStatusWorkflowFamily.FEATURE_GOAL -> null
      }?.updatedAt

    return listOfNotNull(fromWorkflow, item.stateEnteredAt).maxOrNull()
  }

  private fun goalLeaseHeartbeatAt(item: WorkItem): Instant? {
    val lease = unitOfWork.goalRunnerControls.controlState(item.workflowId).executionLease ?: return null
    return lease.heartbeatAt
  }

  private fun latestParentGoalChildUpdatedAt(item: WorkItem): Instant? {
    val childIds = unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(item.workflowId)
    if (childIds.isEmpty()) return null
    return unitOfWork.workflowStates
      .getAll(WorkflowFamily.TASK_RUNTIME, childIds.toSet())
      .values
      .mapNotNull { it.updatedAt }
      .maxOrNull()
  }
}

internal fun GoalRunnerStatusProjection.recordedActiveDurationMs(): Long? =
  activeDurationMs.takeIf { it > 0 || liveActiveDurationAnchor() != null }

internal fun GoalRunnerStatusProjection.liveActiveDurationAnchor(): Instant? =
  parseInstantOrNull(activeDurationAsOf).takeIf { executionLiveness == ExecutionLiveness.LIVE }

internal fun GoalRunnerStatusProjection.recordedSubtaskActiveDurationMs(): Long? =
  subtaskActiveDurationMs.takeIf { it > 0 || liveSubtaskActiveDurationAnchor() != null }

internal fun GoalRunnerStatusProjection.liveSubtaskActiveDurationAnchor(): Instant? =
  parseInstantOrNull(subtaskActiveDurationAsOf).takeIf { executionLiveness == ExecutionLiveness.LIVE }
