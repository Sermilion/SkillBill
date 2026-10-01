package skillbill.infrastructure.sqlite.telemetry.redaction

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.SqliteTestDiagnostics
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.ensureDatabase
import skillbill.infrastructure.sqlite.reconcileStaleTelemetrySessions
import skillbill.infrastructure.sqlite.telemetry.StaleSessionReconciliationPolicy
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ISSUE_KEY = "SKILL-163"

class TelemetryAnonymousRedactionTest {
  @Test
  fun `every builder redacts issue_key at anonymous and keeps the raw key at full`() {
    listOf("anonymous" to false, "full" to true).forEach { (level, raw) ->
      withConnection { connection ->
        driveGoalLifecycle(connection, level)

        val payloads = storedPayloads(connection)
        assertEquals(
          setOf(
            "skillbill_goal_started",
            "skillbill_goal_subtask_finished",
            "skillbill_goal_finished",
            "skillbill_goal_issue_finished",
            "skillbill_feature_task_runtime_started",
          ),
          payloads.keys,
        )
        payloads.forEach { (eventName, payloadJson) ->
          val issueKey = property(payloadJson, "issue_key")
          assertTrue(issueKey != null, "$eventName must retain the issue_key property at $level")
          if (raw) {
            assertEquals(ISSUE_KEY, issueKey, "$eventName must carry the raw key at full")
          } else {
            assertEquals(
              redactIssueKey(ISSUE_KEY, level, telemetryRedactionSalt(connection)),
              issueKey,
              "$eventName must carry the stable substitute at anonymous",
            )
          }
        }
      }
    }
  }

  @Test
  fun `persisted payload_json carries no raw issue key at anonymous and carries it at full`() {
    withConnection { connection ->
      driveGoalLifecycle(connection, "anonymous")
      storedPayloads(connection).forEach { (eventName, payloadJson) ->
        assertFalse(payloadJson.contains(ISSUE_KEY), "$eventName payload_json must not hold the raw key at rest")
      }
    }
    withConnection { connection ->
      driveGoalLifecycle(connection, "full")
      storedPayloads(connection).values.forEach { payloadJson ->
        assertTrue(payloadJson.contains(ISSUE_KEY), "full level must persist the raw key")
      }
    }
  }

  @Test
  fun `the substitute correlates events to one another by issue`() {
    withConnection { connection ->
      driveGoalLifecycle(connection, "anonymous")

      val substitutes = storedPayloads(connection).values.mapNotNull { property(it, "issue_key") }.toSet()
      assertEquals(1, substitutes.size, "one issue must map to exactly one substitute across all events")
    }
  }

  @Test
  fun `a goal issue finished event emitted through stale reconciliation redacts at anonymous`() {
    withConnection { connection ->
      seedAbandonedGoalIssue(connection)

      reconcileStaleTelemetrySessions(
        connection,
        Clock.systemUTC(),
        level = "anonymous",
        policy = StaleSessionReconciliationPolicy(goalIssueAbandonmentDays = 14L),
      )

      val payload =
        requireNotNull(storedPayloads(connection)["skillbill_goal_issue_finished"]) {
          "stale reconciliation must emit the goal issue finished event"
        }
      assertFalse(payload.contains(ISSUE_KEY), "stale reconciliation must redact at anonymous")
      assertEquals(
        redactIssueKey(ISSUE_KEY, "anonymous", telemetryRedactionSalt(connection)),
        property(payload, "issue_key"),
      )
    }
  }

  @Test
  fun `no enqueued outbox payload carries a repo property`() {
    withConnection { connection ->
      driveGoalLifecycle(connection, "full")

      storedPayloads(connection).forEach { (eventName, payloadJson) ->
        assertTrue(
          property(payloadJson, "repo") == null,
          "$eventName must not upload a repo property",
        )
      }
    }
  }

  @Test
  fun `correlation ids derived from the issue key are redacted at anonymous and raw at full`() {
    listOf("anonymous", "full").forEach { level ->
      withConnection { connection ->
        val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
        store.goalSubtaskFinished(
          GoalSubtaskFinishedRecord(
            issueKey = ISSUE_KEY,
            workflowId = "$ISSUE_KEY:subtask:2",
            subtaskId = 2,
            subtaskName = "skipped-subtask",
            status = "skipped",
            startedAt = "2026-06-23T10:00:00Z",
            finishedAt = "2026-06-23T10:15:00Z",
            durationMs = 900_000,
            attemptCount = 1,
            blockedReason = null,
          ),
          level,
        )

        val payload = requireNotNull(storedPayloads(connection)["skillbill_goal_subtask_finished"])
        if (level == "full") {
          assertEquals("$ISSUE_KEY:subtask:2", property(payload, "workflow_id"))
        } else {
          assertFalse(payload.contains(ISSUE_KEY), "payload_json must not hold the raw key in any field")
          assertEquals(
            "${redactIssueKey(ISSUE_KEY, level, telemetryRedactionSalt(connection))}:subtask:2",
            property(payload, "workflow_id"),
          )
        }
      }
    }
  }

  @Test
  fun `the redaction salt never appears in any payload`() {
    withConnection { connection ->
      driveGoalLifecycle(connection, "anonymous")
      val salt = telemetryRedactionSalt(connection)

      storedPayloads(connection).values.forEach { payloadJson ->
        assertFalse(payloadJson.contains(salt), "the salt must never leave the machine")
      }
    }
  }

  private fun driveGoalLifecycle(
    connection: Connection,
    level: String,
  ) {
    val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
    store.goalStarted(startedRecord("wf-1", parentWorkflowId = "parent-1"), level)
    store.goalSubtaskFinished(
      GoalSubtaskFinishedRecord(
        issueKey = ISSUE_KEY,
        workflowId = "wf-1",
        subtaskId = 1,
        subtaskName = "subtask-1",
        status = "complete",
        startedAt = "2026-06-23T10:00:00Z",
        finishedAt = "2026-06-23T10:15:00Z",
        durationMs = 900_000,
        attemptCount = 1,
        blockedReason = null,
      ),
      level,
    )
    store.goalFinished(
      GoalFinishedRecord(
        issueKey = ISSUE_KEY,
        workflowId = "wf-1",
        status = "completed",
        startedAt = "2026-06-23T10:00:00Z",
        finishedAt = "2026-06-23T10:30:00Z",
        durationMs = 1_800_000,
        subtasksComplete = 1,
        subtasksBlocked = 0,
        subtasksSkipped = 0,
        mode = "runtime",
        parentWorkflowId = "parent-1",
      ),
      level,
    )
    store.goalIssueFinished(
      GoalIssueFinishedRecord(
        issueKey = ISSUE_KEY,
        parentWorkflowId = "parent-1",
        status = "completed",
        subtasksComplete = 1,
        subtasksBlocked = 0,
        subtasksSkipped = 0,
        finishedAt = "2026-06-23T10:30:00Z",
        mode = "runtime",
      ),
      level,
    )
    store.featureTaskRuntimeStarted(
      FeatureTaskRuntimeStartedRecord(
        sessionId = "session-1",
        featureSize = "MEDIUM",
        issueKey = ISSUE_KEY,
        featureName = "anonymous redaction",
      ),
      level,
    )
  }

  private fun startedRecord(
    workflowId: String,
    parentWorkflowId: String,
  ): GoalStartedRecord =
    GoalStartedRecord(
      issueKey = ISSUE_KEY,
      featureName = "anonymous redaction",
      workflowId = workflowId,
      subtaskTotal = 1,
      resumed = false,
      startedAt = "2026-06-23T10:00:00Z",
      mode = "runtime",
      parentWorkflowId = parentWorkflowId,
    )

  private fun seedAbandonedGoalIssue(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate(
        """
        INSERT INTO goal_issue_progress (
          parent_workflow_id, issue_key, total_invocations, total_blocks,
          total_resumes, first_started_at, last_activity_at, last_blocked_at,
          latest_segment_workflow_id, last_blocked_segment_workflow_id, mode
        ) VALUES (
          'goal-parent', '$ISSUE_KEY', 2, 6, 1,
          strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-20 days'),
          datetime('now', '-20 days'), datetime('now', '-20 days'),
          'goal-parent:seg:2', 'goal-parent:seg:2', 'runtime'
        )
        """.trimIndent(),
      )
      statement.executeUpdate(
        """
        INSERT INTO goal_run_sessions (
          workflow_id, issue_key, subtask_total, resumed, started_at, status,
          finished_at, finished_duration_ms, subtasks_complete, subtasks_blocked, subtasks_skipped, mode
        ) VALUES (
          'goal-parent:seg:2', '$ISSUE_KEY', 3, 1, datetime('now', '-20 days'), 'blocked',
          datetime('now', '-20 days'), 1000, 1, 1, 1, 'runtime'
        )
        """.trimIndent(),
      )
    }
  }

  private fun storedPayloads(connection: Connection): Map<String, String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT event_name, payload_json FROM telemetry_outbox ORDER BY id").use { resultSet ->
        buildMap {
          while (resultSet.next()) {
            put(resultSet.getString("event_name"), resultSet.getString("payload_json"))
          }
        }
      }
    }

  private fun property(
    payloadJson: String,
    name: String,
  ): String? =
    JsonCodec.parseObjectOrNull(payloadJson)
      ?.get(name)
      ?.let(JsonCodec::jsonElementToValue)
      ?.toString()

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("skillbill-anonymous-redaction").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }
}
