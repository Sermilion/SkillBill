package skillbill.infrastructure.sqlite.workerlease

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.DatabaseAccessOperation
import skillbill.infrastructure.sqlite.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.databaseAccessError
import skillbill.infrastructure.sqlite.workflow.goalrunner.runner.GoalRunnerControlStore
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.RuntimeWorkerLeaseQuery
import skillbill.ports.db.model.RuntimeWorkerLease
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException

@Inject
class SQLiteRuntimeWorkerLeases(
  private val database: DatabaseSessionFactory,
) : RuntimeWorkerLeaseQuery {
  override fun unexpiredLeases(now: Instant): List<RuntimeWorkerLease> {
    if (!database.databaseExists()) return emptyList()
    val dbPath = database.resolveDbPath()
    return DatabaseRuntime.openReadConnectionAt(dbPath).use { openDb ->
      try {
        val connection = openDb.connection
        (standalonePhaseLeases(connection) + workerLeases(connection) + goalRunnerLeases(connection))
          .filter { lease -> lease.expiresAt?.isAfter(now) ?: true }
          .map { lease -> RuntimeWorkerLease(lease.holder, lease.process) }
      } catch (error: SQLException) {
        throw databaseAccessError(dbPath, DatabaseAccessOperation.READ, error)
      }
    }
  }

  private fun standalonePhaseLeases(connection: Connection): List<RecordedLease> {
    if (!connection.hasColumns(STANDALONE_TABLE, STANDALONE_COLUMNS)) return emptyList()
    return connection.rows(
      """
      SELECT invocation_id, phase_id, owner_host_identity, owner_boot_identity, owner_pid, owner_birth_token,
             lease_expires_at
      FROM $STANDALONE_TABLE
      WHERE finished_at IS NULL AND owner_pid IS NOT NULL AND owner_host_identity IS NOT NULL
        AND owner_boot_identity IS NOT NULL AND owner_birth_token IS NOT NULL
      """.trimIndent(),
    ) {
      RecordedLease(
        holder = "phase ${getString("phase_id")} (${getString("invocation_id")})",
        process = processIdentity("owner_host_identity", "owner_boot_identity", "owner_pid", "owner_birth_token"),
        expiresAt = parsedInstant(getString("lease_expires_at")),
      )
    }
  }

  private fun workerLeases(connection: Connection): List<RecordedLease> {
    if (!connection.hasColumns(WORKER_TABLE, WORKER_COLUMNS)) return emptyList()
    return connection.rows(
      "SELECT workflow_id, host_identity, boot_identity, pid, process_birth_token, expires_at FROM $WORKER_TABLE",
    ) {
      RecordedLease(
        holder = "workflow ${getString("workflow_id")}",
        process = processIdentity("host_identity", "boot_identity", "pid", "process_birth_token"),
        expiresAt = parsedInstant(getString("expires_at")),
      )
    }
  }

  private fun goalRunnerLeases(connection: Connection): List<RecordedLease> {
    if (!connection.hasColumns(GOAL_CONTROL_TABLE, GOAL_CONTROL_COLUMNS)) return emptyList()
    val controls = GoalRunnerControlStore(connection)
    return connection.rows("SELECT parent_workflow_id FROM $GOAL_CONTROL_TABLE") { getString("parent_workflow_id") }
      .mapNotNull { parentWorkflowId ->
        controls.controlState(parentWorkflowId).executionLease?.let { lease ->
          RecordedLease(
            holder = "goal $parentWorkflowId",
            process =
              FeatureTaskRuntimeProcessIdentity(
                hostIdentity = lease.hostIdentity,
                bootIdentity = lease.bootIdentity,
                pid = lease.pid,
                processBirthToken = lease.processBirthToken,
              ),
            expiresAt = lease.expiresAtInstant,
          )
        }
      }
  }

  private class RecordedLease(
    val holder: String,
    val process: FeatureTaskRuntimeProcessIdentity,
    val expiresAt: Instant?,
  )

  private companion object {
    const val STANDALONE_TABLE = "standalone_phase_status"
    const val WORKER_TABLE = "feature_task_runtime_worker_leases"
    const val GOAL_CONTROL_TABLE = "goal_runner_controls"
    val STANDALONE_COLUMNS =
      setOf(
        "invocation_id",
        "phase_id",
        "finished_at",
        "lease_expires_at",
        "owner_host_identity",
        "owner_boot_identity",
        "owner_pid",
        "owner_birth_token",
      )
    val WORKER_COLUMNS =
      setOf("workflow_id", "host_identity", "boot_identity", "pid", "process_birth_token", "expires_at")
    val GOAL_CONTROL_COLUMNS = setOf("parent_workflow_id", "control_state_json")
  }
}

private fun Connection.hasColumns(
  table: String,
  required: Set<String>,
): Boolean {
  val present = rows("PRAGMA table_info($table)") { getString("name") }.toSet()
  return present.containsAll(required)
}

private fun <T> Connection.rows(
  sql: String,
  map: ResultSet.() -> T,
): List<T> =
  createStatement().use { statement ->
    statement.executeQuery(sql).use { rows ->
      buildList {
        while (rows.next()) add(rows.map())
      }
    }
  }

private fun ResultSet.processIdentity(
  hostColumn: String,
  bootColumn: String,
  pidColumn: String,
  birthTokenColumn: String,
): FeatureTaskRuntimeProcessIdentity =
  FeatureTaskRuntimeProcessIdentity(
    hostIdentity = getString(hostColumn),
    bootIdentity = getString(bootColumn),
    pid = getLong(pidColumn),
    processBirthToken = getString(birthTokenColumn),
  )

private fun parsedInstant(raw: String?): Instant? =
  raw?.let {
    try {
      Instant.parse(it)
    } catch (_: DateTimeParseException) {
      null
    }
  }
