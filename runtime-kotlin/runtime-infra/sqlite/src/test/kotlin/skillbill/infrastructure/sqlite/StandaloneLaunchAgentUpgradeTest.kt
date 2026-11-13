package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.install.model.SupportedAgent
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StandaloneLaunchAgentUpgradeTest {
  @Test
  fun `populated schema 126 and 127 upgrades roll back interrupted normalization and recover on reopen`() {
    listOf(126, 127).forEach { previousVersion ->
      val dbPath = Files.createTempDirectory("launch-agent-upgrade").resolve("runtime.db")
      val previousProvider = charArrayOf('j', 'u', 'n', 'i', 'e').concatToString()
      val agents =
        SupportedAgent.supportedIds.associateWith { it } +
          mapOf(
            "previous" to previousProvider,
            "missing" to null,
            "retired" to "retired-agent",
            "sentinel" to "unavailable",
          )
      DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
        seed(connection, previousVersion, agents)
      }
      val interrupted =
        DatabaseMigrations.migrations.map { migration ->
          if (migration.version == 128) {
            DatabaseMigration(migration.version, migration.name) { connection: Connection ->
              migration.apply(connection, SqliteTestDiagnostics)
              error("interrupted before migration commit")
            }
          } else {
            migration
          }
        }
      DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
        val before = preservedRows(connection)
        val ledgerBefore = ledger(connection)
        assertFailsWith<IllegalStateException> {
          DatabaseMigrations.apply(connection, SqliteTestDiagnostics, interrupted)
        }
        assertEquals(agents, launchAgents(connection))
        assertEquals(before, preservedRows(connection))
        assertEquals(ledgerBefore, ledger(connection))
        assertEquals(previousVersion.toString(), scalar(connection, "PRAGMA user_version"))
      }
      val expected =
        agents.mapValues { (_, agent) ->
          if (agent == null || agent in SupportedAgent.supportedIds) agent else "unavailable"
        }
      repeat(2) {
        DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)
        DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
          assertEquals(expected, launchAgents(connection))
          assertEquals(DesktopReadSchema.VERSION.toString(), scalar(connection, "PRAGMA user_version"))
          assertEquals(DatabaseMigrations.migrations.map { it.version to it.name }, ledger(connection))
          assertEquals("preserved-run", scalar(connection, "SELECT workflow_id FROM feature_task_workflows"))
          assertEquals(PRESERVED_ARTIFACTS, scalar(connection, "SELECT artifacts_json FROM feature_task_workflows"))
          assertEquals(
            agents.keys.sorted().map { "$it|invocation-$it|complete|completed-$it" },
            preservedRows(connection),
          )
        }
      }
    }
  }

  private fun seed(
    connection: Connection,
    previousVersion: Int,
    agents: Map<String, String?>,
  ) {
    connection.createStatement().use { statement ->
      statement.execute("DELETE FROM schema_migrations WHERE version > $previousVersion")
      statement.execute("PRAGMA user_version = $previousVersion")
    }
    connection.prepareStatement(
      "INSERT INTO feature_task_workflows(workflow_id, mode, contract_version, artifacts_json) VALUES (?, ?, ?, ?)",
    ).use { statement ->
      statement.setString(1, "preserved-run")
      statement.setString(2, "prose")
      statement.setString(3, "1.0")
      statement.setString(4, PRESERVED_ARTIFACTS)
      statement.executeUpdate()
    }
    connection.prepareStatement(
      """
      INSERT INTO standalone_phase_status (
        execution_id, repository_identity, branch_correlation, invocation_id, phase_id,
        status_store_id, run_sequence, status_revision, lifecycle_state, current_step,
        started_at, updated_at, lease_owner, lease_generation, lease_expires_at,
        launch_agent_id, terminal_result
      ) VALUES (?, 'repo-root-realpath-v1:/checkout', 'main', ?, 'validate', 'phase-store',
        '1', '2', 'terminal', 'complete', '2026-11-13T10:00:00Z', '2026-11-13T11:00:00Z',
        'owner', 1, '2026-11-13T11:00:00Z', ?, ?)
      """.trimIndent(),
    ).use { statement ->
      agents.forEach { (id, agent) ->
        statement.setString(1, id)
        statement.setString(2, "invocation-$id")
        statement.setString(3, agent)
        statement.setString(4, "completed-$id")
        statement.executeUpdate()
      }
    }
  }

  private fun launchAgents(connection: Connection): Map<String, String?> =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT execution_id, launch_agent_id FROM standalone_phase_status").use { rows ->
        buildMap {
          while (rows.next()) put(rows.getString(1), rows.getString(2))
        }
      }
    }

  private fun preservedRows(connection: Connection): List<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT execution_id, invocation_id, current_step, terminal_result " +
          "FROM standalone_phase_status ORDER BY execution_id",
      ).use { rows ->
        buildList {
          while (rows.next()) add((1..4).joinToString("|") { rows.getString(it) })
        }
      }
    }

  private fun ledger(connection: Connection): List<Pair<Int, String>> =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT version, name FROM schema_migrations ORDER BY version").use { rows ->
        buildList {
          while (rows.next()) add(rows.getInt(1) to rows.getString(2))
        }
      }
    }

  private fun scalar(
    connection: Connection,
    sql: String,
  ): String? =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { rows ->
        check(rows.next())
        rows.getString(1)
      }
    }

  private companion object {
    const val PRESERVED_ARTIFACTS =
      """{"completed_work":["implement"],"commits":["abc123"],"history":"original proof"}"""
  }
}
