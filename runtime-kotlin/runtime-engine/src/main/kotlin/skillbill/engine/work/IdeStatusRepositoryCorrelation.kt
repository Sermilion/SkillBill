package skillbill.engine.work

import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy

class IdeStatusRepositoryCorrelation(
  private val unitOfWork: UnitOfWork,
  private val repositoryIdentity: String,
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
    return identity.repositoryIdentity == repositoryIdentity
  }

  private fun matchesGoalRepository(item: WorkItem): Boolean? {
    val bound = unitOfWork.goalRunnerControls.controlState(item.workflowId).repositoryIdentity
    return when {
      bound == null -> {
        val issueKey = item.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) ?: return null
        val childrenHere =
          unitOfWork.workflowStates
            .findGoalChildFeatureTaskCandidates(issueKey, repositoryIdentity)
        val childCountAnywhere = unitOfWork.workflowStates.countGoalChildIdentities(issueKey)
        childrenHere.isNotEmpty() || childCountAnywhere == 0
      }
      bound == repositoryIdentity -> true
      else -> false
    }
  }

  private fun matchesVerifyRepository(item: WorkItem): Boolean? {
    val issueKey = item.issueKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (verifyIssueRepositoryCorrelation(issueKey)) {
      VerifyRepoCorrelation.SAME_REPO -> true
      VerifyRepoCorrelation.OTHER_REPO -> false
      VerifyRepoCorrelation.UNKNOWN -> null
    }
  }

  private fun verifyIssueRepositoryCorrelation(issueKey: String): VerifyRepoCorrelation {
    val normalized = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey)
    var sawSameRepo = false
    var sawOtherRepo = false
    for (other in unitOfWork.workList.list(limit = null)) {
      if (other.issueKey?.let(FeatureTaskExecutionIdentityPolicy::canonicalIssueKey) != normalized) continue
      when (correlateSameIssueWork(other, normalized)) {
        VerifyRepoCorrelation.SAME_REPO -> sawSameRepo = true
        VerifyRepoCorrelation.OTHER_REPO -> sawOtherRepo = true
        VerifyRepoCorrelation.UNKNOWN -> Unit
      }
    }
    return when {
      sawSameRepo -> VerifyRepoCorrelation.SAME_REPO
      sawOtherRepo -> VerifyRepoCorrelation.OTHER_REPO
      else -> VerifyRepoCorrelation.UNKNOWN
    }
  }

  private fun correlateSameIssueWork(
    other: WorkItem,
    normalizedIssueKey: String,
  ): VerifyRepoCorrelation =
    when (other.workflowKind) {
      WorkItemKind.FEATURE_TASK_PROSE,
      WorkItemKind.FEATURE_TASK_RUNTIME,
      -> {
        val identity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(other.workflowId)
        when {
          identity == null -> VerifyRepoCorrelation.UNKNOWN
          identity.repositoryIdentity == repositoryIdentity -> VerifyRepoCorrelation.SAME_REPO
          else -> VerifyRepoCorrelation.OTHER_REPO
        }
      }
      WorkItemKind.FEATURE_GOAL -> correlateGoalForVerify(other.workflowId, normalizedIssueKey)
      WorkItemKind.FEATURE_VERIFY -> VerifyRepoCorrelation.UNKNOWN
    }

  private fun correlateGoalForVerify(
    workflowId: String,
    normalizedIssueKey: String,
  ): VerifyRepoCorrelation {
    val bound = unitOfWork.goalRunnerControls.controlState(workflowId).repositoryIdentity
    return when {
      bound == repositoryIdentity -> VerifyRepoCorrelation.SAME_REPO
      bound == null -> {
        val childrenHere =
          unitOfWork.workflowStates
            .findGoalChildFeatureTaskCandidates(normalizedIssueKey, repositoryIdentity)
        val childCountAnywhere = unitOfWork.workflowStates.countGoalChildIdentities(normalizedIssueKey)
        when {
          childrenHere.isNotEmpty() -> VerifyRepoCorrelation.SAME_REPO
          childCountAnywhere > 0 -> VerifyRepoCorrelation.OTHER_REPO
          else -> VerifyRepoCorrelation.UNKNOWN
        }
      }
      else -> VerifyRepoCorrelation.OTHER_REPO
    }
  }
}

private enum class VerifyRepoCorrelation {
  SAME_REPO,
  OTHER_REPO,
  UNKNOWN,
}
