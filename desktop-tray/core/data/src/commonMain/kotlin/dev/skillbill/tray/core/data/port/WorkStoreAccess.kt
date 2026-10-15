package dev.skillbill.tray.core.data.port

import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.WorkSection

/** One candidate the SQL selection returned; [sortTime] orders and continues the page. */
data class CandidateRow(val id: String, val sortTime: String, val deadline: String?)

/** A bounded, query-level selection: filters, ordering and the continuation key are all applied in SQL. */
data class CandidateRequest(
  val section: WorkSection,
  val cutoff: String,
  val through: String,
  val afterTime: String?,
  val afterId: String?,
  val filter: String,
  val limit: Int,
  val historyFilter: HistoryFilter,
)

class CandidateResult(val rows: List<CandidateRow>, val planIndexSearches: Int, val planScans: Int)

/** A read-only connection to the store file, valid for as long as it is the file that was opened. */
interface WorkStoreConnection {
  suspend fun candidates(request: CandidateRequest): CandidateResult

  /** True while the opened file still exists and is the same file. */
  fun isCurrent(): Boolean

  /** Releases every handle on the file; a failure still releases the rest and is rethrown. */
  suspend fun close()
}

interface WorkStoreAccess {
  /**
   * Opens a read-only connection. It never creates, migrates or repairs the store, and refuses a store whose schema
   * is not the one this app reads.
   */
  suspend fun open(databasePath: String): WorkStoreConnection

  /** True only when the file is known not to exist. */
  fun isMissing(databasePath: String): Boolean
}
