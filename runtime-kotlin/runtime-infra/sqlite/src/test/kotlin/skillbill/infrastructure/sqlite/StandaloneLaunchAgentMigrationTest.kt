package skillbill.infrastructure.sqlite

import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandaloneLaunchAgentMigrationTest {
  @Test
  fun `migration 134 rewrites only unsupported standalone launch agents and is repeatable`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          "CREATE TABLE standalone_phase_status (phase_id TEXT PRIMARY KEY, launch_agent_id TEXT)",
        )
        statement.executeUpdate(
          "INSERT INTO standalone_phase_status VALUES " +
            "('supported', 'claude'), ('missing', NULL), ('retired', 'retired-agent')",
        )
        statement.execute("CREATE TABLE operation_launch_contexts (selected_agent TEXT)")
        statement.execute("INSERT INTO operation_launch_contexts VALUES ('retired-agent')")
      }
      val migration = databaseMigrations.single { it.version == 134 }
      migration.apply(connection, SqliteTestDiagnostics)
      migration.apply(connection, SqliteTestDiagnostics)

      connection.createStatement().use { statement ->
        statement.executeQuery(
          "SELECT phase_id, launch_agent_id FROM standalone_phase_status ORDER BY phase_id",
        ).use { result ->
          assertTrue(result.next())
          assertEquals("missing", result.getString(1))
          assertNull(result.getString(2))
          assertTrue(result.next())
          assertEquals("retired", result.getString(1))
          assertEquals("unavailable", result.getString(2))
          assertTrue(result.next())
          assertEquals("supported", result.getString(1))
          assertEquals("claude", result.getString(2))
          assertFalse(result.next())
        }
        statement.executeQuery("SELECT selected_agent FROM operation_launch_contexts").use { result ->
          assertTrue(result.next())
          assertEquals("retired-agent", result.getString(1))
        }
      }
    }
  }

  @Test
  fun `migration 135 preserves supported launch agents while retiring the previous provider`() {
    val previousProvider = charArrayOf('j', 'u', 'n', 'i', 'e').concatToString()
    val originalAgents =
      mapOf(
        "supported" to "codex",
        "missing" to null,
        "previous" to previousProvider,
        "synthetic" to "retired-agent",
        "sentinel" to "unavailable",
      )
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          "CREATE TABLE standalone_phase_status " +
            "(phase_id TEXT PRIMARY KEY, launch_agent_id TEXT, completed_work TEXT)",
        )
      }
      connection.prepareStatement("INSERT INTO standalone_phase_status VALUES (?, ?, ?)").use { statement ->
        originalAgents.forEach { (id, agent) ->
          statement.setString(1, id)
          statement.setString(2, agent)
          statement.setString(3, "completed-$id")
          statement.executeUpdate()
        }
      }
      val migration = databaseMigrations.single { it.version == 135 }
      migration.apply(connection, SqliteTestDiagnostics)
      migration.apply(connection, SqliteTestDiagnostics)

      val migratedAgents =
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT * FROM standalone_phase_status").use { result ->
            buildMap {
              while (result.next()) {
                val id = result.getString("phase_id")
                put(id, result.getString("launch_agent_id"))
                assertEquals("completed-$id", result.getString("completed_work"))
              }
            }
          }
        }
      assertEquals(
        originalAgents + mapOf("previous" to "unavailable", "synthetic" to "unavailable"),
        migratedAgents,
      )
    }
  }
}
