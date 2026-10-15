package skillbill.engine.work

import skillbill.engine.work.model.IdeStatusQueryWork
import skillbill.idestatus.model.IdeStatusDurableWorkflowState
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.ports.idestatus.model.StandaloneEligibleSlice
import skillbill.ports.idestatus.model.StandalonePhaseExecutionAnchor
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkStatusCandidateQuery
import java.time.Instant

/** Rows one status page reads. */
internal const val STATUS_CANDIDATE_PAGE_SIZE = 100

/** Most rows one status read pulls per source (work items, then standalone executions); history past it is not scanned. */
internal const val STATUS_CANDIDATE_BUDGET = 500

/** Times one status request reads again after its collection saw the work store change underneath it. */
internal const val STATUS_READ_ATTEMPTS = 3

/** Per-row lookups an unresolved candidate costs: repository correlation, execution identity and liveness anchor. */
internal const val CANDIDATE_ROW_LOOKUPS = 3

/** The durable workflow states whose lifecycle is still live, so a status page reaches them before any history. */
private val liveWorkflowStates: Set<String> =
  IdeStatusDurableWorkflowState.entries.map { it.wireValue }.filter { state ->
    IdeStatusPolicy.workflowLifecycle(state) in
      setOf(IdeStatusLifecycleState.ACTIVE, IdeStatusLifecycleState.PAUSED, IdeStatusLifecycleState.BLOCKED)
  }.toSet()

/**
 * Pages work items live-first (newest-started within each group) until a short page or [STATUS_CANDIDATE_BUDGET] rows,
 * so a live workflow is never behind an arbitrary newest-N cut of history.
 */
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

/**
 * Pages the live slice to its end, then the settled slice, each strictly after the previous page's last execution, so a
 * long settled tail never starves a live execution of the budget.
 */
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
        unitOfWork.standalonePhaseStatuses.readEligible(repositoryIdentity, branch, observedAt, limit, after, slice)
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
