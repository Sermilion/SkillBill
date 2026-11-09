package skillbill.engine.work

import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkItem
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope

internal class IdeStatusRepositoryCorrelation(
  private val unitOfWork: UnitOfWork,
  private val repositoryIdentity: String,
  private val primaryIdentity: String,
) {
  fun matches(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
  ): Boolean? =
    when (family) {
      IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME ->
        matchesFeatureTaskRepository(item.workflowId)
      IdeStatusWorkflowFamily.FEATURE_GOAL ->
        matchesGoalRepository(item)
      IdeStatusWorkflowFamily.FEATURE_VERIFY ->
        matchesVerifyRepository(item)
    }

  private fun matchesFeatureTaskRepository(workflowId: String): Boolean? {
    val identity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(workflowId) ?: return null
    return identity.repositoryIdentity == repositoryIdentity ||
      (identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD && identity.repositoryIdentity == primaryIdentity)
  }

  private fun matchesGoalRepository(item: WorkItem): Boolean? {
    val bound = unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity
    return when {
      bound == null -> {
        val issueKey = item.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) ?: return null
        val childrenHere =
          setOf(repositoryIdentity, primaryIdentity).any { identity ->
            unitOfWork.workflowStates.findGoalChildFeatureTaskCandidates(issueKey, identity).isNotEmpty()
          }
        val childCountAnywhere = unitOfWork.workflowStates.countGoalChildIdentities(issueKey)
        childrenHere || childCountAnywhere == 0
      }
      bound == repositoryIdentity || bound == primaryIdentity -> true
      else -> false
    }
  }

  private fun matchesVerifyRepository(item: WorkItem): Boolean? {
    unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)?.let { registration ->
      return registration.repositoryIdentity == repositoryIdentity
    }
    val issueKey = item.issueKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey)
    return when (val lookup = unitOfWork.workList.repositoryIdentityForIssue(normalized, item.workflowId)) {
      IssueRepositoryIdentityLookup.Missing -> null
      is IssueRepositoryIdentityLookup.Unique -> lookup.repositoryIdentity == repositoryIdentity
      is IssueRepositoryIdentityLookup.Ambiguous -> repositoryIdentity in lookup.repositoryIdentities
    }
  }
}
