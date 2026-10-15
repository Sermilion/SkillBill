package skillbill.ports.work.model

data class WorkStatusCandidateQuery(
  val afterKey: String?,
  val limit: Int,
  val liveStates: Set<String>,
)

data class WorkStatusCandidatePage(val items: List<WorkItem>, val lastKey: String?)
