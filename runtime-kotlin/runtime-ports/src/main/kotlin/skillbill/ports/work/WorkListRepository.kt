package skillbill.ports.work

import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkGoalDependencyAnchor
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalParentCandidate
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkStoreObservation
import skillbill.workflow.decomposition.model.DecompositionSubtask

interface WorkListRepository : WorkCandidateRepository {
  fun observation(): WorkStoreObservation

  fun goalSubtaskPage(
    workflowId: String,
    offset: Int,
    limit: Int,
    completedOnly: Boolean = false,
    afterSubtaskId: Int? = null,
  ): WorkGoalSubtaskPage?

  fun goalSubtasksByIds(
    workflowId: String,
    subtaskIds: Set<Int>,
  ): List<DecompositionSubtask>

  fun goalParentCandidatePage(
    issueKey: String,
    repositoryIdentity: String?,
    offset: Int,
    limit: Int,
  ): List<WorkGoalParentCandidate>

  fun goalDependencyPage(
    workflowId: String,
    offset: Int,
    limit: Int,
    subtaskId: Int? = null,
    after: WorkGoalDependencyAnchor? = null,
  ): WorkGoalDependencyPage

  fun list(limit: Int? = null): List<WorkItem>

  fun findByWorkflowId(workflowId: String): List<WorkItem>

  fun repositoryIdentityForIssue(
    normalizedIssueKey: String,
    excludedWorkflowId: String,
  ): IssueRepositoryIdentityLookup

  fun repositoryIdentityForGoalChildren(parentWorkflowId: String): IssueRepositoryIdentityLookup
}
