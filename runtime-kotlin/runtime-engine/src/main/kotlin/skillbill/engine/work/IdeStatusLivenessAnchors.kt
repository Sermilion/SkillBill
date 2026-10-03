package skillbill.engine.work

import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItem
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.time.Instant

class IdeStatusLivenessAnchors(
  private val unitOfWork: UnitOfWork,
  private val repositoryIdentity: String,
) {
  fun authoritativeUpdatedAt(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): Instant? {
    val fromWorkflow =
      when (family) {
        IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, item.workflowId)
        IdeStatusWorkflowFamily.FEATURE_VERIFY ->
          unitOfWork.workflowStates.get(WorkflowFamily.VERIFY, item.workflowId)
        IdeStatusWorkflowFamily.FEATURE_GOAL -> null
      }?.updatedAt
        ?: latestGoalChildUpdatedAt(item, family)

    return listOfNotNull(fromWorkflow, item.stateEnteredAt, goalLeaseHeartbeatAt(item, family))
      .maxOrNull()
  }

  private fun goalLeaseHeartbeatAt(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): Instant? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL) return null
    val lease = unitOfWork.goalRunnerControls.controlState(item.workflowId).executionLease ?: return null
    return lease.heartbeatAt
  }

  private fun latestGoalChildUpdatedAt(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): Instant? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL) return null
    val issueKey =
      item.issueKey
        ?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey)
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return unitOfWork.workflowStates
      .findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity)
      .mapNotNull { parseInstantOrNull(it.workflow.updatedAt) }
      .maxOrNull()
  }
}
