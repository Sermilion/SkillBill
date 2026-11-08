package skillbill.infrastructure.sqlite.core.migration.area.idestatus.buildrun

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.COMMAND
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.ENDED_AT
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.REPOSITORY_IDENTITY
import skillbill.infrastructure.sqlite.core.schema.AgentBuildRunsKeys.STARTED_AT
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.model.AgentBuildHistory
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildPrevious
import skillbill.ports.idestatus.model.AgentBuildRun
import skillbill.ports.idestatus.model.AgentBuildRunStart
import java.sql.Connection
import java.time.Duration
import java.time.Instant

internal class SqliteAgentBuildRunStore(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
  private val transactionActive: Boolean,
) : AgentBuildRunRepository {
  override fun start(start: AgentBuildRunStart): Unit =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      prepareStatement(INSERT_BUILD_SQL).use { statement ->
        statement.bindAll(
          start.buildId,
          start.runId,
          start.parentRunId,
          start.repositoryIdentity,
          start.command,
          start.shortCommand,
          start.tool,
          start.projectKind,
          start.pid,
          start.startedAt.toBuildText(),
        )
        statement.executeUpdate()
      }
    }

  override fun settle(
    buildId: String,
    endedAt: Instant,
    outcome: AgentBuildOutcome,
    exitCode: Int?,
  ): Boolean =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      val settled =
        prepareStatement(SETTLE_BUILD_SQL).use { statement ->
          statement.bindAll(endedAt.toBuildText(), outcome.wireValue, exitCode, buildId)
          statement.executeUpdate()
        } == 1
      if (settled) pruneSettled(buildId)
      settled
    }

  private fun Connection.pruneSettled(buildId: String) {
    val key =
      prepareStatement(SELECT_BUILD_KEY_SQL).use { statement ->
        statement.setString(1, buildId)
        statement.executeQuery().use { rows ->
          if (rows.next()) rows.getString(REPOSITORY_IDENTITY) to rows.getString(COMMAND) else null
        }
      } ?: return
    prepareStatement(PRUNE_SETTLED_SQL).use { statement ->
      statement.bindAll(key.first, key.second, key.first, key.second)
      statement.executeUpdate()
    }
  }

  override fun abandonOpen(runId: String): Int =
    connection.inNestedWriteTransaction(diagnostics, transactionActive) {
      prepareStatement(ABANDON_OPEN_SQL).use { statement ->
        statement.setString(1, AgentBuildOutcome.UNKNOWN.wireValue)
        statement.setString(2, runId)
        statement.executeUpdate()
      }
    }

  override fun latestForRun(runIds: Set<String>): AgentBuildRun? {
    if (runIds.isEmpty()) return null
    val ids = runIds.toList()
    return connection.prepareStatement(latestForRunSql(ids.size)).use { statement ->
      (ids + ids).forEachIndexed { index, id -> statement.setString(index + 1, id) }
      statement.executeQuery().use { rows -> if (rows.next()) rows.readBuildRun() else null }
    }
  }

  override fun history(
    repositoryIdentity: String,
    command: String,
    startedBefore: Instant,
  ): AgentBuildHistory {
    val settled =
      connection.prepareStatement(HISTORY_SQL).use { statement ->
        statement.bindAll(repositoryIdentity, command, startedBefore.toBuildText())
        statement.executeQuery().use { rows ->
          buildList {
            while (rows.next()) {
              val ended = Instant.parse(rows.getString(ENDED_AT))
              val durationMs = Duration.between(Instant.parse(rows.getString(STARTED_AT)), ended).toMillis()
              add(AgentBuildPrevious(rows.readSettledOutcome(), durationMs.coerceAtLeast(0), ended))
            }
          }
        }
      }
    return AgentBuildHistory(usualMs = averageMs(settled.map { it.durationMs }), previous = settled.firstOrNull())
  }

  private fun averageMs(durations: List<Long>): Long? = if (durations.isEmpty()) null else durations.average().toLong()
}
