package skillbill.ports.work

import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkBoardCandidateRow
import skillbill.ports.work.model.WorkGoalDependencyAnchor
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalParentCandidate
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkStatusCandidatePage
import skillbill.ports.work.model.WorkStatusCandidateQuery
import skillbill.ports.work.model.WorkStoreObservation
import skillbill.workflow.decomposition.model.DecompositionSubtask

object EmptyWorkListRepository : WorkListRepository {
  override fun observation() = WorkStoreObservation("test-store", 0)

  override fun repositoryIdentityForGoalChildren(parentWorkflowId: String) = IssueRepositoryIdentityLookup.Missing

  override fun boardCandidates(query: WorkBoardCandidateQuery): List<WorkBoardCandidateRow> = emptyList()

  override fun repositoryIdentityForIssue(
    normalizedIssueKey: String,
    excludedWorkflowId: String,
  ) = IssueRepositoryIdentityLookup.Missing

  override fun goalSubtaskPage(
    workflowId: String,
    offset: Int,
    limit: Int,
    completedOnly: Boolean,
    afterSubtaskId: Int?,
  ): WorkGoalSubtaskPage? = null

  override fun goalSubtasksByIds(
    workflowId: String,
    subtaskIds: Set<Int>,
  ): List<DecompositionSubtask> = emptyList()

  override fun goalParentCandidatePage(
    issueKey: String,
    repositoryIdentity: String?,
    offset: Int,
    limit: Int,
  ): List<WorkGoalParentCandidate> = emptyList()

  override fun goalDependencyPage(
    workflowId: String,
    offset: Int,
    limit: Int,
    subtaskId: Int?,
    after: WorkGoalDependencyAnchor?,
  ): WorkGoalDependencyPage = WorkGoalDependencyPage(emptyList(), null)

  override fun statusCandidates(query: WorkStatusCandidateQuery) = WorkStatusCandidatePage(emptyList(), null)

  override fun list(limit: Int?): List<WorkItem> = emptyList()

  override fun findByWorkflowId(workflowId: String): List<WorkItem> = emptyList()
}
