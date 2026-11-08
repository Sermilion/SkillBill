package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.idestatus.WorkBoardInvalidationKeys
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkBoardInvalidationMigrationTest {
  @Test
  fun `previous schema upgrade preserves workflow payload and interrupted migration can retry`() {
    val home = Files.createTempDirectory("board-queue-upgrade")
    val path = home.resolve("state.db")
    DatabaseRuntime.ensureDatabase(path).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, artifacts_json) " +
            "VALUES ('preserved-run', 'prose', '1.0', '{\"evidence\":\"retain this\"}')",
        )
        statement.execute("DROP TRIGGER work_board_invalidate_source")
        statement.execute("DROP TABLE ${WorkBoardInvalidationKeys.TABLE}")
        statement.executeUpdate("DELETE FROM schema_migrations WHERE version = 117")
        statement.execute("PRAGMA user_version = 116")
      }
    }
    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
      val interruptedMigrations =
        DatabaseMigrations.migrations.map { migration ->
          if (migration.version != 117) {
            migration
          } else {
            DatabaseMigration(migration.version, migration.name) { target, diagnostics ->
              migration.apply(target, diagnostics)
              error("interrupt before migration commit")
            }
          }
        }
      assertFailsWith<IllegalStateException> {
        DatabaseMigrations.apply(connection, SqliteTestDiagnostics, interruptedMigrations)
      }
      assertEquals("116", scalar(connection, "PRAGMA user_version"))
      assertEquals(
        "0",
        scalar(connection, "SELECT count(*) FROM sqlite_master WHERE name = 'work_board_invalidations'"),
      )
    }
    DatabaseRuntime.migrateExistingDatabase(path, SqliteTestDiagnostics)
    DatabaseRuntime.migrateExistingDatabase(path, SqliteTestDiagnostics)
    DatabaseRuntime.ensureDatabase(path).use { connection ->
      assertEquals(
        "{\"evidence\":\"retain this\"}",
        scalar(connection, "SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = 'preserved-run'"),
      )
      assertEquals("preserved-run", scalar(connection, "SELECT work_id FROM work_board_invalidations"))
      assertEquals("0", scalar(connection, "SELECT complete FROM work_board_read_model_state"))
      assertEquals("ok", scalar(connection, "PRAGMA integrity_check"))
      assertTrue(scalar(connection, "PRAGMA user_version").toInt() > 116)
    }
  }
}

private fun scalar(
  connection: Connection,
  sql: String,
): String =
  connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { rows ->
      check(rows.next())
      rows.getString(1)
    }
  }
