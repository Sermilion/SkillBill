package skillbill.ports.work.model

/** One keyset page of status candidates: live workflows first, then newest-started, bounded by [limit]. */
data class WorkStatusCandidateQuery(
  val afterKey: String?,
  val limit: Int,
  val liveStates: Set<String>,
)

data class WorkStatusCandidatePage(val items: List<WorkItem>, val lastKey: String?)
