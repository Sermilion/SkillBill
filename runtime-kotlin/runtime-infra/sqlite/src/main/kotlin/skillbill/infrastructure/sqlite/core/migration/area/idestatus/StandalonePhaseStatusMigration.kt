package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.ops.bindAll
import java.sql.Connection
import java.util.UUID

internal object StandalonePhaseStatusMigration {
  fun apply(connection: Connection) {
    createSchema(connection)
    backfillWorkflowExecutions(connection)
  }

  private fun createSchema(connection: Connection) {
    createExecutionRegistry(connection)
    createStandalonePhaseStatus(connection)
    createWorkflowExecution(connection)
    createIndexes(connection)
  }

  private fun createExecutionRegistry(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS ide_status_execution_registry (
          repository_identity TEXT PRIMARY KEY,
          status_store_id TEXT NOT NULL UNIQUE,
          run_sequence_high_water TEXT NOT NULL CHECK (
            run_sequence_high_water <> '' AND run_sequence_high_water NOT GLOB '*[^0-9]*'
          ),
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """.trimIndent(),
      )
    }
  }

  private fun createStandalonePhaseStatus(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS standalone_phase_status (
          execution_id TEXT PRIMARY KEY,
          repository_identity TEXT NOT NULL,
          branch_correlation TEXT NOT NULL,
          issue_key TEXT,
          workflow_id TEXT,
          invocation_id TEXT NOT NULL UNIQUE,
          phase_id TEXT NOT NULL,
          status_store_id TEXT NOT NULL,
          run_sequence TEXT NOT NULL CHECK (run_sequence <> '' AND run_sequence <> '0' AND run_sequence NOT GLOB '*[^0-9]*'),
          status_revision TEXT NOT NULL CHECK (status_revision <> '' AND status_revision <> '0' AND status_revision NOT GLOB '*[^0-9]*'),
          lifecycle_state TEXT NOT NULL,
          current_step TEXT NOT NULL,
          current_activity TEXT,
          started_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          finished_at TEXT,
          active_duration_ms INTEGER,
          active_duration_as_of TEXT,
          lease_owner TEXT NOT NULL,
          lease_generation INTEGER NOT NULL CHECK (lease_generation > 0),
          lease_expires_at TEXT NOT NULL,
          terminal_result TEXT
        )
        """.trimIndent(),
      )
    }
  }

  private fun createWorkflowExecution(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        CREATE TABLE IF NOT EXISTS ide_status_workflow_execution (
          execution_id TEXT PRIMARY KEY,
          repository_identity TEXT NOT NULL,
          branch_correlation TEXT NOT NULL,
          issue_key TEXT,
          workflow_id TEXT NOT NULL,
          invocation_id TEXT NOT NULL UNIQUE,
          status_store_id TEXT NOT NULL,
          run_sequence TEXT NOT NULL CHECK (run_sequence <> '' AND run_sequence <> '0' AND run_sequence NOT GLOB '*[^0-9]*'),
          status_revision TEXT NOT NULL CHECK (status_revision <> '' AND status_revision <> '0' AND status_revision NOT GLOB '*[^0-9]*'),
          lifecycle_state TEXT NOT NULL,
          started_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """.trimIndent(),
      )
    }
  }

  private fun createIndexes(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_standalone_phase_status_correlation " +
          "ON standalone_phase_status(repository_identity, branch_correlation, run_sequence)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_standalone_phase_status_retention " +
          "ON standalone_phase_status(repository_identity, updated_at, lifecycle_state)",
      )
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_ide_status_workflow_execution_lookup " +
          "ON ide_status_workflow_execution(workflow_id, started_at, execution_id)",
      )
    }
  }

  private fun backfillWorkflowExecutions(connection: Connection) {
    connection.prepareStatement(
      """
      SELECT workflows.workflow_id, workflows.issue_key, workflows.started_at,
             workflows.workflow_status, identities.repository_identity
      FROM feature_task_workflows AS workflows
      JOIN feature_task_execution_identities AS identities
        ON identities.workflow_id = workflows.workflow_id
      ORDER BY identities.repository_identity, workflows.started_at, workflows.workflow_id
      """.trimIndent(),
    ).use { statement ->
      statement.executeQuery().use { rows ->
        while (rows.next()) {
          val workflowId = rows.getString("workflow_id")
          if (connection.workflowExecutionExists(workflowId)) continue
          val repositoryIdentity = rows.getString("repository_identity")
          val storeId = connection.ensureStatusStore(repositoryIdentity)
          val sequence = connection.allocateStatusSequence(repositoryIdentity)
          val startedAt = rows.getString("started_at")
          connection.prepareStatement(
            """
            INSERT OR IGNORE INTO ide_status_workflow_execution(
              execution_id, repository_identity, branch_correlation, issue_key, workflow_id,
              invocation_id, status_store_id, run_sequence, status_revision, lifecycle_state,
              started_at, updated_at
            ) VALUES (?, ?, 'HEAD', ?, ?, ?, ?, ?, '1', ?, ?, ?)
            """.trimIndent(),
          ).use { insert ->
            insert.bindAll(
              "workflow-$workflowId-backfill",
              repositoryIdentity,
              rows.getString("issue_key"),
              workflowId,
              "backfill-$workflowId",
              storeId,
              sequence,
              rows.getString("workflow_status"),
              startedAt,
              startedAt,
            )
            insert.executeUpdate()
          }
        }
      }
    }
  }

  private fun Connection.workflowExecutionExists(workflowId: String): Boolean =
    prepareStatement("SELECT 1 FROM ide_status_workflow_execution WHERE workflow_id = ? LIMIT 1").use { statement ->
      statement.bindAll(workflowId)
      statement.executeQuery().use { rows -> rows.next() }
    }

  private fun Connection.ensureStatusStore(repositoryIdentity: String): String {
    prepareStatement(
      "SELECT status_store_id FROM ide_status_execution_registry WHERE repository_identity = ?",
    ).use { statement ->
      statement.bindAll(repositoryIdentity)
      statement.executeQuery().use { rows -> if (rows.next()) return rows.getString(1) }
    }
    val storeId = UUID.randomUUID().toString()
    prepareStatement(
      "INSERT OR IGNORE INTO ide_status_execution_registry(" +
        "repository_identity, status_store_id, run_sequence_high_water, created_at, updated_at) " +
        "VALUES (?, ?, '0', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
    ).use { statement ->
      statement.bindAll(repositoryIdentity, storeId)
      statement.executeUpdate()
    }
    return prepareStatement(
      "SELECT status_store_id FROM ide_status_execution_registry WHERE repository_identity = ?",
    ).use { statement ->
      statement.bindAll(repositoryIdentity)
      statement.executeQuery().use { rows ->
        if (rows.next()) rows.getString(1) else error("Status registry backfill was not persisted.")
      }
    }
  }

  private fun Connection.allocateStatusSequence(repositoryIdentity: String): String {
    val current =
      prepareStatement(
        "SELECT run_sequence_high_water FROM ide_status_execution_registry WHERE repository_identity = ?",
      ).use { statement ->
        statement.bindAll(repositoryIdentity)
        statement.executeQuery().use { rows ->
          if (rows.next()) rows.getString(1) else error("Status registry is missing during backfill.")
        }
      }
    val next = incrementDecimal(current)
    prepareStatement(
      "UPDATE ide_status_execution_registry SET run_sequence_high_water = ?, " +
        "updated_at = CURRENT_TIMESTAMP WHERE repository_identity = ?",
    ).use { statement ->
      statement.bindAll(next, repositoryIdentity)
      statement.executeUpdate()
    }
    return next
  }

  private fun incrementDecimal(value: String): String {
    val digits = value.toCharArray()
    var carry = 1
    for (index in digits.lastIndex downTo 0) {
      if (carry == 0) break
      if (digits[index] == '9') {
        digits[index] = '0'
      } else {
        digits[index] = (digits[index].code + 1).toChar()
        carry = 0
      }
    }
    return if (carry == 1) "1${digits.concatToString()}" else digits.concatToString()
  }
}
