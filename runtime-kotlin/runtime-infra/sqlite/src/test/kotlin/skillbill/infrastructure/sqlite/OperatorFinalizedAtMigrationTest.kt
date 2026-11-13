package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.contracts.persistence.WorkBoardReadModelKeys
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.addOperatorFinalizedAt
import skillbill.infrastructure.sqlite.core.schema.OPERATOR_FINALIZED_AT_COLUMN
import skillbill.infrastructure.sqlite.workflow.WorkflowRowUpsertRequest
import skillbill.infrastructure.sqlite.workflow.featuretask.StandalonePhaseStatusStore
import skillbill.infrastructure.sqlite.workflow.upsertWorkflowRow
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OperatorFinalizedAtMigrationTest {
  @Test
  fun `add operator finalized at backfills successful rows and invalidates the read model`() {
    val root = Files.createTempDirectory("operator-finalized-at")
    val path = root.resolve("runtime.db")
    val clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC)
    val finished = clock.instant()
    try {
      seedPreviousVersionStore(path, clock, finished)
      DatabaseRuntime.ensureDatabase(path).use { connection ->
        assertEquals(
          finished.toString(),
          finalizedAt(connection, "standalone_phase_status", "execution_id", "execution-done"),
        )
        assertNull(finalizedAt(connection, "standalone_phase_status", "execution_id", "execution-failed"))
        assertNull(finalizedAt(connection, "standalone_phase_status", "execution_id", "execution-blocked"))
        assertNull(finalizedAt(connection, "standalone_phase_status", "execution_id", "execution-canceled"))
        assertEquals(
          finished.toString(),
          finalizedAt(connection, "feature_verify_workflows", "workflow_id", "verify-done"),
        )
        assertNull(finalizedAt(connection, "feature_verify_workflows", "workflow_id", "verify-failed"))
        assertEquals(
          "terminal",
          scalar(connection, "standalone_phase_status", "lifecycle_state", "execution_id", "execution-done"),
        )
        assertEquals(
          finished.toString(),
          scalar(connection, "standalone_phase_status", "finished_at", "execution_id", "execution-done"),
        )
        assertEquals(0, completeFlag(connection))
        assertEquals(DesktopReadSchema.VERSION, userVersion(connection))
        val distinct = finished.plusSeconds(90).toString()
        connection.prepareStatement(
          "UPDATE standalone_phase_status SET $OPERATOR_FINALIZED_AT_COLUMN = ? WHERE execution_id = ?",
        ).use { statement ->
          statement.setString(1, distinct)
          statement.setString(2, "execution-done")
          statement.executeUpdate()
        }
        addOperatorFinalizedAt(connection)
        assertEquals(
          distinct,
          finalizedAt(connection, "standalone_phase_status", "execution_id", "execution-done"),
        )
        assertEquals(
          finished.toString(),
          finalizedAt(connection, "feature_verify_workflows", "workflow_id", "verify-done"),
        )
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun seedPreviousVersionStore(
    path: Path,
    clock: Clock,
    finished: Instant,
  ) {
    DatabaseRuntime.ensureDatabase(path).use { connection ->
      val store = StandalonePhaseStatusStore(connection, clock, SqliteTestDiagnostics, false)
      terminalStandalone(store, "done", "terminal", finished)
      terminalStandalone(store, "failed", "failed", finished)
      terminalStandalone(store, "blocked", "blocked", finished)
      terminalStandalone(store, "canceled", "canceled", finished)
      upsertVerify(connection, clock, "verify-done", WorkflowStatus.COMPLETED, finished)
      upsertVerify(connection, clock, "verify-failed", WorkflowStatus.FAILED, finished)
      rewindToVersion126(connection)
      connection.autoCommit = false
      addOperatorFinalizedAt(connection)
      connection.rollback()
      connection.autoCommit = true
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "UPDATE ${WorkBoardReadModelKeys.STATE_TABLE} SET ${WorkBoardReadModelKeys.COMPLETE} = 1",
        )
      }
    }
  }

  private fun rewindToVersion126(connection: Connection) {
    connection.createStatement().use { statement ->
      listOf("insert", "update", "delete", "update_activity").forEach { suffix ->
        statement.execute("DROP TRIGGER IF EXISTS desktop_change_standalone_phase_status_$suffix")
        statement.execute("DROP TRIGGER IF EXISTS desktop_change_feature_verify_workflows_$suffix")
      }
      statement.execute("ALTER TABLE standalone_phase_status DROP COLUMN $OPERATOR_FINALIZED_AT_COLUMN")
      statement.execute("ALTER TABLE feature_verify_workflows DROP COLUMN $OPERATOR_FINALIZED_AT_COLUMN")
      statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'add-operator-finalized-at'")
      statement.execute("PRAGMA user_version = 126")
    }
  }

  private fun terminalStandalone(
    store: StandalonePhaseStatusStore,
    name: String,
    lifecycle: String,
    finished: Instant,
  ) {
    val registered =
      store.register(
        StandalonePhaseStatusRegistration(
          "repo",
          "main",
          null,
          null,
          name,
          "validate",
          "execution-$name",
          "active",
          "validate",
          finished,
          "owner",
          1,
          finished.plusSeconds(3600),
        ),
      )
    store.update(
      StandalonePhaseStatusUpdate(
        executionId = registered.executionId,
        expectedRevision = registered.statusRevision,
        leaseOwner = registered.leaseOwner,
        leaseGeneration = registered.leaseGeneration,
        lifecycleState = lifecycle,
        currentStep = "validate",
        currentActivity = null,
        updatedAt = finished,
        finishedAt = finished,
        terminalResult = name,
      ),
    )
  }

  private fun upsertVerify(
    connection: Connection,
    clock: Clock,
    workflowId: String,
    status: WorkflowStatus,
    finished: Instant,
  ) {
    connection.upsertWorkflowRow(
      "feature_verify_workflows",
      WorkflowStateRecord(
        workflowId = workflowId,
        sessionId = "",
        workflowName = "bill-feature-verify",
        contractVersion = "0.4",
        workflowStatus = status.wireValue,
        currentStepId = "",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = finished.toString(),
        updatedAt = finished.toString(),
        finishedAt = finished.toString(),
      ),
      WorkflowRowUpsertRequest(
        clock = clock,
        workflowSnapshotValidator = SqliteTestWorkflowSnapshotValidator,
        defaultContractVersion = "0.4",
        acceptedSnapshotMutation = true,
      ),
    )
  }

  private fun finalizedAt(
    connection: Connection,
    table: String,
    key: String,
    id: String,
  ): String? = scalar(connection, table, OPERATOR_FINALIZED_AT_COLUMN, key, id)

  private fun scalar(
    connection: Connection,
    table: String,
    column: String,
    key: String,
    id: String,
  ): String? =
    connection.prepareStatement("SELECT $column FROM $table WHERE $key = ?").use { statement ->
      statement.setString(1, id)
      statement.executeQuery().use { rows ->
        if (!rows.next()) return null
        rows.getString(column)
      }
    }

  private fun completeFlag(connection: Connection): Int =
    connection.prepareStatement(
      "SELECT ${WorkBoardReadModelKeys.COMPLETE} FROM ${WorkBoardReadModelKeys.STATE_TABLE} " +
        "WHERE ${WorkBoardReadModelKeys.ID} = 1",
    ).use { statement ->
      statement.executeQuery().use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }

  private fun userVersion(connection: Connection): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA user_version").use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }
}
