package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class StandaloneOutputHistoryMigrationTest {
  @Test
  fun `output history upgrades schema 105 without rewriting an existing workflow`() {
    val dbPath = Files.createTempDirectory("plan-output-upgrade").resolve("runtime.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feature_task_workflows(workflow_id, mode, contract_version) " +
            "VALUES ('preserved-plan', 'prose', '1.0')",
        )
        statement.execute("DROP TABLE standalone_plan_activity")
        statement.execute("DROP TABLE standalone_phase_output_lines")
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version >= 106")
        statement.execute("PRAGMA user_version = 105")
      }
    }
    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(
        1,
        scalarInt(connection, "SELECT count(*) FROM feature_task_workflows WHERE workflow_id = 'preserved-plan'"),
      )
      assertEquals(0, scalarInt(connection, "SELECT count(*) FROM standalone_phase_output_lines"))
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
    }
  }

  private fun scalarInt(
    connection: Connection,
    sql: String,
  ): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { rows ->
        check(rows.next())
        rows.getInt(1)
      }
    }
}
