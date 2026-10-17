package skillbill.ports.work

import skillbill.ports.work.model.IssueRepositoryIdentityLookup
import skillbill.ports.work.model.WorkBoardCandidateQuery
import skillbill.ports.work.model.WorkBoardCandidateRow
import skillbill.ports.work.model.WorkGoalDependencyPage
import skillbill.ports.work.model.WorkGoalParentCandidate
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkListListRead
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
  ): WorkGoalDependencyPage

  fun list(limit: Int? = null): WorkListListRead

  fun findByWorkflowId(workflowId: String): List<WorkItem>

  fun repositoryIdentityForIssue(
    normalizedIssueKey: String,
    excludedWorkflowId: String,
  ): IssueRepositoryIdentityLookup

  fun repositoryIdentityForGoalChildren(parentWorkflowId: String): IssueRepositoryIdentityLookup
}
