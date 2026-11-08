package skillbill.infrastructure.sqlite

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.core.migration.area.WorkflowStateVersionMigration
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowStateVersionMigrationTest {
  @Test
  fun `workflow upgrades preserve completed work history and unsupported source records on repeated installation`() {
    val path = Files.createTempDirectory("workflow-version-upgrade").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(path, NoopRuntimeDiagnostics).use { connection ->
      seed(connection)
      val before = sourceRows(connection)
      DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics)
      DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics)
      assertEquals(WORKFLOW_STATE_CONTRACT_VERSION, WorkflowStateVersionMigration.TARGET_VERSION)
      assertEquals(before, sourceRows(connection))
      assertEquals("0.4", version(connection, "completed"))
      assertEquals("0.4", version(connection, "running"))
      assertEquals("0.4", version(connection, "running-0.1"))
      assertEquals("0.4", version(connection, "running-0.2"))
      assertEquals("0.4", version(connection, "verify-completed", "feature_verify_workflows"))
      assertEquals("99.0", version(connection, "unsupported"))
      assertEquals("0.3", version(connection, "malformed"))
      assertEquals("0.3", version(connection, "prose"))
      assertEquals(0, scalarInt(connection, "SELECT complete FROM work_board_read_model_state"))
    }
  }

  @Test
  fun `an interrupted workflow migration rolls back records and ledger then succeeds on retry`() {
    val path = Files.createTempDirectory("workflow-version-rollback").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(path, NoopRuntimeDiagnostics).use { connection ->
      seed(connection)
      val before = sourceRows(connection)
      connection.createStatement().use { statement ->
        statement.execute(
          "CREATE TRIGGER interrupt_workflow_upgrade BEFORE UPDATE OF complete ON work_board_read_model_state " +
            "BEGIN SELECT RAISE(ABORT, 'interrupted upgrade'); END",
        )
      }
      assertFailsWith<SQLException> { DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics) }
      assertEquals(before, sourceRows(connection))
      assertEquals("0.3", version(connection, "completed"))
      assertEquals("0.2", version(connection, "verify-completed", "feature_verify_workflows"))
      assertEquals(1, scalarInt(connection, "SELECT complete FROM work_board_read_model_state"))
      assertEquals(107, scalarInt(connection, "PRAGMA user_version"))
      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM schema_migrations WHERE version = 108"))
      connection.createStatement().use { it.execute("DROP TRIGGER interrupt_workflow_upgrade") }
      DatabaseMigrations.apply(connection, NoopRuntimeDiagnostics)
      assertEquals("0.4", version(connection, "completed"))
      assertEquals(before, sourceRows(connection))
    }
  }

  private fun seed(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 108")
      statement.execute("PRAGMA user_version = 107")
      statement.executeUpdate("UPDATE work_board_read_model_state SET complete = 1")
    }
    listOf("completed", "running", "running-0.1", "running-0.2", "unsupported", "malformed", "prose").forEach { id ->
      connection.prepareStatement(
        "INSERT INTO feature_task_workflows " +
          "(workflow_id, mode, contract_version, workflow_status, steps_json, artifacts_json, finished_at) " +
          "VALUES (?, ?, ?, ?, ?, ?, ?)",
      ).use { statement ->
        statement.setString(1, id)
        statement.setString(2, if (id == "prose") "prose" else "runtime")
        statement.setString(3, if (id == "unsupported") "99.0" else id.substringAfter("running-", "0.3"))
        statement.setString(4, if (id == "completed") "completed" else "running")
        statement.setString(5, if (id == "malformed") "invalid JSON" else "[]")
        statement.setString(6, """{"saved_history":["accepted"],"commit_sha":"abc123","checkpoint":"retained"}""")
        statement.setString(7, if (id == "completed") "2026-11-07T12:00:00Z" else null)
        statement.executeUpdate()
      }
    }
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        "INSERT INTO feature_verify_workflows " +
          "(workflow_id, contract_version, workflow_status, steps_json, artifacts_json, finished_at) " +
          "VALUES ('verify-completed', '0.2', 'completed', '[]', '{\"history\":[\"accepted\"]}', " +
          "'2026-11-07T12:00:00Z')",
      )
    }
  }

  private fun sourceRows(connection: Connection): List<List<String?>> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT workflow_id, mode, workflow_status, steps_json, artifacts_json, finished_at, updated_at, " +
          "state_entered_at FROM feature_task_workflows " +
          "UNION ALL SELECT workflow_id, workflow_name, workflow_status, steps_json, artifacts_json, " +
          "finished_at, updated_at, state_entered_at FROM feature_verify_workflows ORDER BY workflow_id",
      ).use { rows -> buildList { while (rows.next()) add((1..8).map(rows::getString)) } }
    }

  private fun version(
    connection: Connection,
    id: String,
    table: String = "feature_task_workflows",
  ): String =
    connection.prepareStatement("SELECT contract_version FROM $table WHERE workflow_id = ?").use {
      it.setString(1, id)
      it.executeQuery().use { rows ->
        check(rows.next())
        rows.getString(1)
      }
    }
}
