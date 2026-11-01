package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import skillbill.infrastructure.sqlite.review.stats.ReviewStatsRuntime
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalTelemetryCountColumnsMigrationTest {
  @Test
  fun `migrations 85 and 86 keep legacy goal rows readable with zero counts and no exit reason`() {
    val dbPath = Files.createTempDirectory("skillbill-goal-count-columns").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(
        connection,
        SqliteTestDiagnostics,
        migrationSet = DatabaseMigrations.migrations.filter { it.version <= 84 },
      )
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO goal_run_sessions (workflow_id, issue_key, started_at, status, finished_at, " +
            "finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped, mode) " +
            "VALUES " +
            "('wf-legacy', 'SKILL-1', '2026-06-04T10:00:00Z', 'completed', " +
            "'2026-06-04T11:00:00Z', 1, 1, 0, 0, 'runtime')",
        )
        statement.executeUpdate(
          "INSERT INTO goal_subtask_events (issue_key, workflow_id, subtask_id, subtask_name, status, " +
            "started_at, finished_at, duration_ms, attempt_count) VALUES " +
            "('SKILL-1', 'wf-legacy', 1, 'one', 'complete', " +
            "'2026-06-04T10:00:00Z', '2026-06-04T11:00:00Z', 1, 1)",
        )
      }
      assertTrue(!hasColumn(connection, "goal_subtask_events", "test_probes_killed"))
      assertTrue(!hasColumn(connection, "goal_run_sessions", "goal_assessment_exit_reason"))
    }

    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)

    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      assertTrue(hasColumn(connection, "goal_subtask_events", "test_probes_killed"))
      assertTrue(hasColumn(connection, "goal_run_sessions", "goal_assessment_exit_reason"))
      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.totalSubtaskEvents)
      assertEquals(0L, stats.interactionScenarios.traced)
      assertEquals(0L, stats.testProbes.killed)
      assertEquals(0L, stats.goalAssessment.rounds)
      assertEquals(emptyMap(), stats.goalAssessment.exitReasonCounts)
      assertNull(exitReason(connection, "wf-legacy"))
    }
  }

  private fun hasColumn(
    connection: Connection,
    table: String,
    column: String,
  ): Boolean =
    connection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name = ?").use { statement ->
      statement.setString(1, table)
      statement.setString(2, column)
      statement.executeQuery().use { it.next() }
    }

  private fun exitReason(
    connection: Connection,
    workflowId: String,
  ): String? =
    connection.prepareStatement(
      "SELECT goal_assessment_exit_reason FROM goal_run_sessions WHERE workflow_id = ?",
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows ->
        rows.next()
        rows.getString(1)
      }
    }
}
