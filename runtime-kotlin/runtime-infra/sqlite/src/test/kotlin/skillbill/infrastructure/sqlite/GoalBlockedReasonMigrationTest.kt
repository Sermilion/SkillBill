package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalBlockedReasonMigrationTest {
  @Test
  fun `goal blocked reason migration upgrades version 97 and preserves goal sessions`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-goal-blocked-reason").resolve("legacy.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO goal_run_sessions (workflow_id, issue_key, started_at, status) " +
            "VALUES ('wfl-legacy', 'SKILL-457', '2026-11-01T14:00:00Z', 'blocked')",
        )
        statement.execute("ALTER TABLE goal_run_sessions DROP COLUMN blocked_reason")
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'record-goal-blocked-reason'")
        statement.execute("PRAGMA user_version = 97")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      assertEquals(1, scalarInt(connection, "SELECT count(*) FROM goal_run_sessions WHERE workflow_id = 'wfl-legacy'"))
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT blocked_reason FROM goal_run_sessions WHERE workflow_id = 'wfl-legacy'").use {
          assertTrue(it.next())
          assertEquals(null, it.getString(1))
        }
      }
    }
  }
}
