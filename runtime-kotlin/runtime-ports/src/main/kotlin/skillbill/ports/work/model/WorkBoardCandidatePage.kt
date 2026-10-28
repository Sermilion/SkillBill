package skillbill.ports.work.model

import java.time.Instant

const val WORK_BOARD_CANDIDATE_BATCH_SIZE = 400

data class WorkBoardCandidateQuery(
  val finishedFrom: Instant,
  val observedAt: Instant,
  val afterKey: String?,
  val limit: Int,
)

data class WorkBoardCandidateRow(val id: String, val key: String)

data class WorkBoardAffectedCandidates(
  val candidates: Set<String>,
  val nonCandidates: Set<String>,
  val requiresRebuild: Boolean = false,
)
