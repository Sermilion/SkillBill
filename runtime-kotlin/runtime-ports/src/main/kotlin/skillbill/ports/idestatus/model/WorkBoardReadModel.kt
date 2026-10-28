package skillbill.ports.idestatus.model

import java.time.Instant

data class WorkBoardReadModelRow(
  val workId: String,
  val item: WorkBoardItem,
  val updatedAt: Instant,
  val searchText: String,
  val reprojectAt: Instant?,
  val projectedAt: Instant,
  val membershipDeadline: Instant?,
)

data class WorkBoardReadModelPageQuery(
  val finishedFrom: Instant,
  val filter: String?,
  val afterKey: WorkBoardReadModelKey?,
  val limit: Int,
)

data class WorkBoardReadModelKey(
  val displayPriority: Int,
  val updatedAtText: String,
  val workId: String,
)

data class WorkBoardReadModelPage(val entries: List<Entry>) {
  sealed interface Entry {
    val workId: String
    val key: WorkBoardReadModelKey

    data class Decoded(val row: WorkBoardReadModelRow, override val key: WorkBoardReadModelKey) : Entry {
      override val workId: String get() = row.workId
    }

    data class Undecodable(override val workId: String, override val key: WorkBoardReadModelKey) : Entry
  }
}
