package skillbill.engine.work.pagination

import skillbill.engine.work.model.IdeStatusQueryWork
import skillbill.idestatus.model.IdeStatusDurableWorkflowState
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.ports.idestatus.model.StandaloneEligiblePage
import skillbill.ports.idestatus.model.StandaloneEligibleSlice
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkStatusCandidateQuery
import java.time.Instant

internal const val STATUS_CANDIDATE_PAGE_SIZE = 100

internal const val STATUS_CANDIDATE_BUDGET = 500

internal const val STATUS_READ_ATTEMPTS = 3

internal const val CANDIDATE_ROW_LOOKUPS = 3

private val liveWorkflowStates: Set<String> =
  IdeStatusDurableWorkflowState.entries.map { it.wireValue }.filter { state ->
    IdeStatusPolicy.workflowLifecycle(state) in
      setOf(IdeStatusLifecycleState.ACTIVE, IdeStatusLifecycleState.PAUSED, IdeStatusLifecycleState.BLOCKED)
  }.toSet()

internal fun readStatusWorkItems(
  unitOfWork: UnitOfWork,
  queryWork: IdeStatusQueryWork?,
): List<WorkItem> {
  val items = mutableListOf<WorkItem>()
  var afterKey: String? = null
  while (items.size < STATUS_CANDIDATE_BUDGET) {
    val limit = minOf(STATUS_CANDIDATE_PAGE_SIZE, STATUS_CANDIDATE_BUDGET - items.size)
    val page = unitOfWork.workList.statusCandidates(WorkStatusCandidateQuery(afterKey, limit, liveWorkflowStates))
    queryWork?.page(page.items.size)
    items += page.items
    afterKey = page.lastKey
    if (page.items.size < limit) return items
  }
  queryWork?.exhaustedBudget()
  return items
}

internal fun readStatusStandaloneRecords(
  unitOfWork: UnitOfWork,
  repositoryIdentity: String,
  branch: String,
  observedAt: Instant,
  queryWork: IdeStatusQueryWork?,
): List<StandalonePhaseStatusRecord> {
  val records = mutableListOf<StandalonePhaseStatusRecord>()
  for (slice in StandaloneEligibleSlice.entries) {
    var after: StandalonePhaseExecutionAnchor? = null
    var exhausted = true
    while (records.size < STATUS_CANDIDATE_BUDGET) {
      val limit = minOf(STATUS_CANDIDATE_PAGE_SIZE, STATUS_CANDIDATE_BUDGET - records.size)
      val page =
        unitOfWork.standalonePhaseStatuses.readEligible(
          repositoryIdentity,
          branch,
          observedAt,
          StandaloneEligiblePage(limit, after, slice),
        )
      queryWork?.page(page.size)
      records += page
      if (page.size < limit) {
        exhausted = false
        break
      }
      after = page.last().let { StandalonePhaseExecutionAnchor(it.runSequence, it.invocationId) }
    }
    if (exhausted) {
      queryWork?.exhaustedBudget()
      break
    }
  }
  return records
}
