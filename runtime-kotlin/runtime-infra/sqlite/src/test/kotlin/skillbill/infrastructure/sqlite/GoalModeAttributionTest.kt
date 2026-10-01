package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.review.stats.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalModeAttributionTest {
  @Test
  fun `prose lifecycle full sequence retains mode=prose row but emits runtime-only goal stats`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      store.goalStarted(startedRecord("wf-prose-1", mode = "prose"), level = "full")
      store.goalSubtaskFinished(subtask(id = 1, workflowId = "wf-prose-1", status = "complete"), "full")
      store.goalSubtaskFinished(subtask(id = 2, workflowId = "wf-prose-1", status = "blocked"), "full")
      store.goalFinished(
        GoalFinishedRecord(
          issueKey = "SKILL-92",
          workflowId = "wf-prose-1",
          status = "blocked",
          startedAt = "2026-06-23T10:00:00Z",
          finishedAt = "2026-06-23T10:30:00Z",
          durationMs = 1_800_000,
          subtasksComplete = 1,
          subtasksBlocked = 1,
          subtasksSkipped = 0,
          mode = "prose",
        ),
        level = "full",
      )

      val storedMode =
        connection.prepareStatement(
          "SELECT mode FROM goal_run_sessions WHERE workflow_id = ?",
        ).use { stmt ->
          stmt.setString(1, "wf-prose-1")
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "prose row should still be present in the store")
            rs.getString("mode")
          }
        }
      assertEquals("prose", storedMode, "Legacy prose row must remain verbatim in storage")

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.finishedRuns)
      assertFalse(stats.byMode.containsKey("prose"), "goal stats must not emit a live prose bucket")
      assertEquals(2, stats.totalSubtaskEvents)
    }
  }

  @Test
  fun `calling goal_started twice with same workflow_id produces exactly one row (idempotent re-emit)`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val record = startedRecord("wf-prose-idem", mode = "prose")

      store.goalStarted(record, level = "full")
      store.goalStarted(record, level = "full")

      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalRuns)
      assertFalse(stats.byMode.containsKey("prose"), "goal stats must not emit a live prose bucket")
    }
  }

  @Test
  fun `calling goal_finished twice with same workflow_id produces exactly one row (idempotent re-emit)`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.goalStarted(startedRecord("wf-prose-fin-idem", mode = "prose"), level = "full")
      val finished =
        GoalFinishedRecord(
          issueKey = "SKILL-92",
          workflowId = "wf-prose-fin-idem",
          status = "completed",
          startedAt = "2026-06-23T10:00:00Z",
          finishedAt = "2026-06-23T10:30:00Z",
          durationMs = 1_800_000,
          subtasksComplete = 1,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          mode = "prose",
        )

      store.goalFinished(finished, level = "full")
      store.goalFinished(finished, level = "full")

      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.finishedRuns)
      assertFalse(stats.byMode.containsKey("prose"), "goal stats must not emit a live prose bucket")
    }
  }

  @Test
  fun `DatabaseColumnMigrations adds mode column to pre-feature goal_run_sessions and legacy rows read as runtime`() {
    withConnection { connection ->

      connection.createStatement().use { stmt ->
        stmt.execute(
          """
          CREATE TABLE IF NOT EXISTS goal_run_sessions (
            workflow_id TEXT PRIMARY KEY NOT NULL,
            issue_key TEXT NOT NULL DEFAULT '',
            feature_name TEXT NOT NULL DEFAULT '',
            subtask_total INTEGER NOT NULL DEFAULT 0,
            resumed INTEGER NOT NULL DEFAULT 0,
            started_at TEXT NOT NULL DEFAULT '',
            status TEXT,
            finished_at TEXT,
            finished_duration_ms INTEGER,
            subtasks_complete INTEGER,
            subtasks_blocked INTEGER,
            subtasks_skipped INTEGER
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement(
        "INSERT INTO goal_run_sessions (workflow_id, issue_key, started_at) VALUES (?, ?, ?)",
      ).use { stmt ->
        stmt.setString(1, "wf-legacy")
        stmt.setString(2, "SKILL-92")
        stmt.setString(3, "2026-06-23T10:00:00Z")
        stmt.executeUpdate()
      }

      DatabaseColumnMigrations.apply(connection)

      val columnNames = tableColumnNames(connection, "goal_run_sessions")
      assertTrue("mode" in columnNames, "mode column should exist after migration")

      val mode =
        connection.prepareStatement(
          "SELECT mode FROM goal_run_sessions WHERE workflow_id = ?",
        ).use { stmt ->
          stmt.setString(1, "wf-legacy")
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getString("mode")
          }
        }
      assertEquals("runtime", mode, "Legacy rows should return 'runtime' via the column DEFAULT")
    }
  }

  @Test
  fun `goal_stats byMode breakdown emits runtime-only buckets and excludes legacy prose rows`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      finishedRun(store, workflowId = "wf-rt-1", mode = "runtime", status = "completed", durationMs = 60_000)
      finishedRun(store, workflowId = "wf-rt-2", mode = "runtime", status = "blocked", durationMs = 120_000)
      finishedRun(store, workflowId = "wf-prose-1", mode = "prose", status = "completed", durationMs = 90_000)

      connection.prepareStatement(
        """
        INSERT INTO goal_run_sessions
          (workflow_id, issue_key, feature_name, subtask_total, resumed, started_at,
           status, finished_at, finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped)
        VALUES (?, 'SKILL-92', 'test', 1, 0, '2026-06-23T08:00:00Z',
                'completed', '2026-06-23T09:00:00Z', 3600000, 1, 0, 0)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, "wf-legacy")
        stmt.executeUpdate()
      }

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(4, stats.totalRuns)

      assertFalse(stats.byMode.containsKey("prose"), "goal stats must not emit a live prose bucket")

      val runtimeStats = requireNotNull(stats.byMode["runtime"])

      assertEquals(3, runtimeStats.totalRuns)
      assertEquals(3, runtimeStats.finishedRuns)
      assertEquals(2, runtimeStats.completedRuns)
      assertEquals(1, runtimeStats.blockedRuns)
    }
  }

  private fun startedRecord(
    workflowId: String,
    mode: String = "runtime",
  ): GoalStartedRecord =
    GoalStartedRecord(
      issueKey = "SKILL-92",
      featureName = "goal mode attribution",
      workflowId = workflowId,
      subtaskTotal = 2,
      resumed = false,
      startedAt = "2026-06-23T10:00:00Z",
      mode = mode,
    )

  private fun subtask(
    id: Int,
    workflowId: String,
    status: String,
  ): GoalSubtaskFinishedRecord =
    GoalSubtaskFinishedRecord(
      issueKey = "SKILL-92",
      workflowId = workflowId,
      subtaskId = id,
      subtaskName = "subtask-$id",
      status = status,
      startedAt = "2026-06-23T10:00:00Z",
      finishedAt = "2026-06-23T10:15:00Z",
      durationMs = 900_000,
      attemptCount = 1,
      blockedReason = if (status == "blocked") "test blocked" else null,
    )

  private fun finishedRun(
    store: LifecycleTelemetryStore,
    workflowId: String,
    mode: String,
    status: String,
    durationMs: Long,
  ) {
    store.goalStarted(startedRecord(workflowId, mode = mode), level = "full")
    store.goalFinished(
      GoalFinishedRecord(
        issueKey = "SKILL-92",
        workflowId = workflowId,
        status = status,
        startedAt = "2026-06-23T10:00:00Z",
        finishedAt = "2026-06-23T11:00:00Z",
        durationMs = durationMs,
        subtasksComplete = if (status == "completed") 1 else 0,
        subtasksBlocked = if (status == "blocked") 1 else 0,
        subtasksSkipped = 0,
        mode = mode,
      ),
      level = "full",
    )
  }

  private fun tableColumnNames(
    connection: Connection,
    tableName: String,
  ): Set<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
      }
    }

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("skillbill-goal-mode-attribution").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }
}
