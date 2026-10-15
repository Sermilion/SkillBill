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

interface WorkListRepository {
  fun observation(): WorkStoreObservation

  fun boardCandidates(query: WorkBoardCandidateQuery): List<WorkBoardCandidateRow>

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

  /** One keyset page of work items, live states first then newest-started; the cursor is the last row's key. */
  fun statusCandidates(query: WorkStatusCandidateQuery): WorkStatusCandidatePage

  fun list(limit: Int? = null): List<WorkItem>

  fun findByWorkflowId(workflowId: String): List<WorkItem>

  fun repositoryIdentityForIssue(
    normalizedIssueKey: String,
    excludedWorkflowId: String,
  ): IssueRepositoryIdentityLookup

  fun repositoryIdentityForGoalChildren(parentWorkflowId: String): IssueRepositoryIdentityLookup
}
