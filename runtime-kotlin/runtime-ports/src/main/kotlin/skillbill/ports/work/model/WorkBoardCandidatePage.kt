package skillbill.ports.work.model

import java.time.Instant

data class WorkBoardCandidateQuery(
  val finishedFrom: Instant,
  val observedAt: Instant,
  val afterKey: String?,
  val limit: Int,
)

data class WorkBoardCandidateRow(val id: String, val key: String)
