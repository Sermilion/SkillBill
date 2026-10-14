package dev.skillbill.tray.core.data.store

import androidx.sqlite.SQLiteConnection
import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.data.port.CandidateRequest
import dev.skillbill.tray.core.data.port.CandidateResult
import dev.skillbill.tray.core.data.port.CandidateRow
import dev.skillbill.tray.core.data.port.WorkStoreAccess
import dev.skillbill.tray.core.data.port.WorkStoreConnection
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.WorkSection
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.error.core.DatabaseAccessOperation
import skillbill.error.core.databaseIncompatible
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Opens the runtime store through Room over a read-only JDBC connection; it never creates or migrates the file. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JvmWorkStoreAccess(private val diagnostics: Diagnostics) : WorkStoreAccess {
  override suspend fun open(databasePath: String): WorkStoreConnection {
    val selected = Path.of(databasePath)
    val identity = storeFileIdentity(selected)
    val room = openWorkRoomDatabase(selected, diagnostics)
    val latestMigration = try {
      room.migrations().latest()
    } catch (failure: Throwable) {
      closeAfterFailure(room, failure)
      throw failure
    }
    if (latestMigration?.version != DesktopReadSchema.VERSION) {
      val failure = databaseIncompatible(selected.toString(), DatabaseAccessOperation.READ)
      closeAfterFailure(room, failure)
      throw failure
    }
    val revisionConnection = try {
      ReadOnlyRoomDriver(diagnostics).open(selected.toString())
    } catch (failure: Throwable) {
      closeAfterFailure(room, failure)
      throw failure
    }
    return RoomWorkStoreConnection(selected, identity, room, revisionConnection, diagnostics)
  }

  override fun isMissing(databasePath: String): Boolean = Files.notExists(Path.of(databasePath))

  private suspend fun closeAfterFailure(room: WorkRoomDatabase, failure: Throwable) {
    try {
      room.close()
    } catch (closeFailure: Throwable) {
      failure.addSuppressed(closeFailure)
      diagnostics.record("room_schema_connection_close_failed")
    }
  }
}

private class RoomWorkStoreConnection(
  private val path: Path,
  private val identity: String,
  private val room: WorkRoomDatabase,
  private val revisionConnection: SQLiteConnection,
  private val diagnostics: Diagnostics,
) : WorkStoreConnection {
  private val accessPlans = ConcurrentHashMap<Pair<WorkSection, HistoryFilter>, Pair<Int, Int>>()

  override suspend fun candidates(request: CandidateRequest): CandidateResult {
    val query = candidateQuery(
      request.section, request.cutoff, request.through, request.afterTime, request.afterId,
      request.filter, request.limit, request.historyFilter,
    )
    val plan = accessPlans.getOrPut(request.section to request.historyFilter) { queryPlan(query.sql) }
    val rows = room.candidates().candidates(query.roomQuery).map { CandidateRow(it.id, it.sort_time, it.deadline) }
    return CandidateResult(rows, plan.first, plan.second)
  }

  override fun isCurrent(): Boolean = Files.exists(path) && identity == storeFileIdentity(path)

  override suspend fun close() {
    var failure: Throwable? = null
    try {
      room.close()
    } catch (closeFailure: Throwable) {
      failure = closeFailure
    }
    try {
      revisionConnection.close()
    } catch (_: Throwable) {
      diagnostics.record("room_revision_connection_close_failed")
    }
    failure?.let { throw it }
  }

  private fun queryPlan(sql: String): Pair<Int, Int> = try {
    revisionConnection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
      var indexSearches = 0
      var scans = 0
      while (statement.step()) {
        val detail = statement.getText(3).orEmpty().uppercase()
        if ("SEARCH" in detail && "INDEX" in detail) indexSearches++
        if ("SCAN" in detail) scans++
      }
      indexSearches to scans
    }
  } catch (failure: Exception) {
    diagnostics.record("candidate_query_plan_unavailable_${failure.javaClass.simpleName.take(32)}")
    0 to 0
  }
}
