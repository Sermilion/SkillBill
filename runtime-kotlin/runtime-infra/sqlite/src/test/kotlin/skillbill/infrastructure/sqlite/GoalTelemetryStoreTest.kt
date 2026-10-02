package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.error.core.GoalTelemetryRowFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.review.stats.ReviewStatsRuntime
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalTelemetryStoreTest {
  @Test
  fun `empty store reports zero runs and no most-recent run`() {
    withConnection { connection ->
      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(0, stats.totalRuns)
      assertEquals(0, stats.finishedRuns)
      assertEquals(0, stats.inProgressRuns)
      assertEquals(0, stats.totalSubtaskEvents)
      assertEquals(mapOf("complete" to 0, "blocked" to 0, "skipped" to 0), stats.subtaskOutcomeCounts)
      assertEquals(
        mapOf("completed" to 0, "paused" to 0, "blocked" to 0, "abandoned" to 0),
        stats.completionStatusCounts,
      )
      assertEquals(0.0, stats.averageRunDurationMs)
      assertNull(stats.mostRecentRun)
    }
  }

  @Test
  fun `write read and aggregate a full blocked run with complete blocked and skipped subtasks`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      seedBlockedRunWithMixedSubtasks(store)

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.finishedRuns)
      assertEquals(0, stats.inProgressRuns)
      assertEquals(0, stats.completedRuns)
      assertEquals(1, stats.blockedRuns)
      assertEquals(1.0, stats.blockedRate)
      assertEquals(
        mapOf("completed" to 0, "paused" to 0, "blocked" to 1, "abandoned" to 0),
        stats.completionStatusCounts,
      )
      assertEquals(mapOf("complete" to 1, "blocked" to 1, "skipped" to 1), stats.subtaskOutcomeCounts)
      assertEquals(3, stats.totalSubtaskEvents)
      assertEquals(1_800_000.0, stats.averageRunDurationMs)
      assertEquals(60_000.0, stats.averageSubtaskDurationMs)
      assertEquals(1.0, stats.averageAttemptCount)

      val mostRecent = requireNotNull(stats.mostRecentRun)
      assertEquals("wf-1", mostRecent.workflowId)
      assertEquals("SKILL-66", mostRecent.issueKey)
      assertEquals("blocked", mostRecent.status)
      assertEquals(1_800_000L, mostRecent.durationMs)
      assertEquals(3, mostRecent.subtaskTotal)

      assertOutboxEmittedOncePerEvent(connection)
      assertGoalTerminalDurationPayloadsUseSeconds(connection)

      assertEquals(1, stats.topBlockedSubtasks.size)
      val blocked = stats.topBlockedSubtasks.single()
      assertEquals(2, blocked.subtaskId)
      assertEquals("validation failed", blocked.blockedReason)
      assertEquals(2, blocked.attemptCount)
    }
  }

  @Test
  fun `top blocked subtasks is empty when no subtasks are blocked`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      finishedRun(store, "wf-a", startedAt = "2026-06-04T08:00:00Z", status = "completed", durationMs = 100_000)
      finishedRun(store, "wf-b", startedAt = "2026-06-04T09:00:00Z", status = "completed", durationMs = 200_000)

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertTrue(stats.topBlockedSubtasks.isEmpty())
    }
  }

  @Test
  fun `top blocked subtasks lists all blocked entries across runs`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      store.goalStarted(startedRecord("wf-x", subtaskTotal = 1, resumed = false), level = "full")
      store.goalSubtaskFinished(
        subtask(id = 1, status = "blocked", durationMs = 60_000, attempts = 1, blockedReason = "test failure"),
        "full",
      )
      store.goalFinished(
        GoalFinishedRecord(
          issueKey = "SKILL-66",
          workflowId = "wf-x",
          status = "blocked",
          startedAt = "2026-06-04T10:00:00Z",
          finishedAt = "2026-06-04T10:01:00Z",
          durationMs = 60_000,
          subtasksComplete = 0,
          subtasksBlocked = 1,
          subtasksSkipped = 0,
          mode = "runtime",
        ),
        level = "full",
      )

      store.goalStarted(startedRecord("wf-y", subtaskTotal = 1, resumed = false), level = "full")
      store.goalSubtaskFinished(
        GoalSubtaskFinishedRecord(
          issueKey = "SKILL-77",
          workflowId = "wf-y",
          subtaskId = 3,
          subtaskName = "subtask-3",
          status = "blocked",
          startedAt = "2026-06-04T11:00:00Z",
          finishedAt = "2026-06-04T11:02:00Z",
          durationMs = 120_000,
          attemptCount = 2,
          blockedReason = "compile error",
        ),
        "full",
      )

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(2, stats.topBlockedSubtasks.size)
    }
  }

  @Test
  fun `most-recent run lookup picks the latest started completed run`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      finishedRun(store, "wf-early", startedAt = "2026-06-04T08:00:00Z", status = "completed", durationMs = 100_000)
      finishedRun(store, "wf-late", startedAt = "2026-06-04T20:00:00Z", status = "completed", durationMs = 300_000)

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(2, stats.totalRuns)
      assertEquals(2, stats.completedRuns)
      assertEquals(1.0, stats.completedRate)
      assertEquals(200_000.0, stats.averageRunDurationMs)
      assertEquals("wf-late", requireNotNull(stats.mostRecentRun).workflowId)
    }
  }

  @Test
  fun `resumed duplicate subtask event is not double counted or re-emitted`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val record = subtask(id = 1, status = "complete", durationMs = 60_000, attempts = 1)

      store.goalSubtaskFinished(record, "full")
      store.goalSubtaskFinished(record, "full")

      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalSubtaskEvents)
      assertEquals(mapOf("complete" to 1, "blocked" to 0, "skipped" to 0), stats.subtaskOutcomeCounts)

      val outbox = pendingOutbox(connection)
      assertEquals(1, outbox.count { it.eventName == "skillbill_goal_subtask_finished" })
    }
  }

  @Test
  fun `paused run aggregates as resumable and is not counted as blocked`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      finishedRun(store, "wf-paused", startedAt = "2026-06-04T10:00:00Z", status = "paused", durationMs = 90_000)

      val stats = ReviewStatsRuntime.goalStats(connection)

      assertEquals(1, stats.totalRuns)
      assertEquals(1, stats.finishedRuns)
      assertEquals(0, stats.inProgressRuns)
      assertEquals(0, stats.completedRuns)
      assertEquals(0, stats.blockedRuns)
      assertEquals(0.0, stats.blockedRate)
      assertEquals(
        mapOf("completed" to 0, "paused" to 1, "blocked" to 0, "abandoned" to 0),
        stats.completionStatusCounts,
      )
      assertEquals("paused", requireNotNull(stats.mostRecentRun).status)
    }
  }

  @Test
  fun `in-progress run counts as not finished and has no terminal status`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      store.goalStarted(startedRecord("wf-open", subtaskTotal = 2, resumed = true), level = "full")

      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalRuns)
      assertEquals(0, stats.finishedRuns)
      assertEquals(1, stats.inProgressRuns)
      assertEquals("", requireNotNull(stats.mostRecentRun).status)
      assertTrue(requireNotNull(stats.mostRecentRun).resumed)
    }
  }

  @Test
  fun `malformed persisted subtask row loud-fails on read`() {
    withConnection { connection ->
      connection.prepareStatement(
        """
        INSERT INTO goal_subtask_events (
          issue_key, workflow_id, subtask_id, subtask_name, status,
          started_at, finished_at, duration_ms, attempt_count
        ) VALUES ('SKILL-66', 'wf-1', 1, 'persistence', 'bogus', '2026-06-04T10:00:00Z', '2026-06-04T10:01:00Z', 60000,
          1)
        """.trimIndent(),
      ).use { it.executeUpdate() }

      val error =
        assertFailsWith<SkillBillRuntimeException> {
          ReviewStatsRuntime.goalStats(connection)
        }
      assertEquals(GoalTelemetryRowFailureCode.MALFORMED, error.code)
    }
  }

  @Test
  fun `emitted telemetry aggregates per-agent finalized and recovery-handoff participant counts`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      store.goalSubtaskFinished(attributedSubtask(1, "codex", listOf("codex")), "full")
      store.goalSubtaskFinished(attributedSubtask(2, "claude", listOf("codex", "claude")), "full")

      val payloads =
        pendingOutbox(connection)
          .filter { it.eventName == "skillbill_goal_subtask_finished" }
          .map { parsePayload(it.payloadJson) }

      payloads.forEachIndexed { index, payload ->
        assertNotNull(payload["finalizing_agent_id"], "payload[$index] must contain finalizing_agent_id key")
        assertIs<String>(payload["finalizing_agent_id"], "payload[$index].finalizing_agent_id must be a String")
        assertNotNull(payload["participating_agent_ids"], "payload[$index] must contain participating_agent_ids key")
        assertIs<List<*>>(payload["participating_agent_ids"], "payload[$index].participating_agent_ids must be a List")
      }

      val finalizedByAgent =
        payloads.map { assertIs<String>(it["finalizing_agent_id"]) }
          .groupingBy { it }.eachCount()
      val handoffParticipantByAgent =
        payloads.flatMap { payload ->
          val finalizer = assertIs<String>(payload["finalizing_agent_id"])
          val participants = assertIs<List<String>>(payload["participating_agent_ids"])
          participants.filter { it != finalizer }
        }.groupingBy { it }.eachCount()

      assertEquals(mapOf("codex" to 1, "claude" to 1), finalizedByAgent)

      assertEquals(mapOf("codex" to 1), handoffParticipantByAgent)
    }
  }

  @Test
  fun `legacy subtask event without agent attribution is accepted and emits an empty participants array`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.goalSubtaskFinished(subtask(1, "complete", 60_000, 1), "full")

      val payload =
        parsePayload(
          pendingOutbox(connection).single { it.eventName == "skillbill_goal_subtask_finished" }.payloadJson,
        )

      assertNull(payload["finalizing_agent_id"])
      assertEquals(emptyList<Any?>(), payload["participating_agent_ids"])
      val stats = ReviewStatsRuntime.goalStats(connection)
      assertEquals(1, stats.totalSubtaskEvents)
    }
  }

  @Test
  fun `goal_subtask_finished payload carries boundary_history fields when workflow and session rows exist`() {
    withConnection { connection ->
      connection.prepareStatement(
        "INSERT INTO feature_task_workflows " +
          "(workflow_id, session_id, mode, contract_version) VALUES (?, ?, 'prose', '1.1.0')",
      ).use {
        it.setString(1, "wfl-history")
        it.setString(2, "fis-history")
        it.executeUpdate()
      }
      connection.prepareStatement(
        "INSERT INTO feature_implement_sessions " +
          "(session_id, boundary_history_value, boundary_history_written) VALUES (?, 'high', 1)",
      ).use {
        it.setString(1, "fis-history")
        it.executeUpdate()
      }

      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.goalSubtaskFinished(
        subtask(id = 1, status = "complete", durationMs = 60_000, attempts = 1).copy(workflowId = "wfl-history"),
        "full",
      )

      val payload =
        parsePayload(
          pendingOutbox(connection).single { it.eventName == "skillbill_goal_subtask_finished" }.payloadJson,
        )
      assertEquals("high", payload["boundary_history_value"])
      assertEquals(true, payload["boundary_history_written"])
    }
  }

  @Test
  fun `goal_subtask_finished payload defaults boundary_history fields when no feature_task_workflows row exists`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.goalSubtaskFinished(
        subtask(id = 1, status = "complete", durationMs = 60_000, attempts = 1).copy(workflowId = "wfl-unknown"),
        "full",
      )

      val payload =
        parsePayload(
          pendingOutbox(connection).single { it.eventName == "skillbill_goal_subtask_finished" }.payloadJson,
        )
      assertEquals("none", payload["boundary_history_value"])
      assertEquals(false, payload["boundary_history_written"])
    }
  }

  @Test
  fun `anonymous goal_subtask_finished payload carries category-prefixed blocked_reason`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      store.goalSubtaskFinished(
        subtask(id = 1, status = "blocked", durationMs = 60_000, attempts = 1, blockedReason = "validation: failed"),
        "anonymous",
      )

      val payload =
        parsePayload(
          pendingOutbox(connection).single { it.eventName == "skillbill_goal_subtask_finished" }.payloadJson,
        )
      assertEquals("validation: failed", payload["blocked_reason"])
    }
  }

  @Test
  fun `goal issue progress aggregates segments and emits completed issue exactly once`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)

      val firstStart =
        startedRecord("wf-parent:seg:1", subtaskTotal = 1, resumed = false, startedAt = "2026-06-04T10:00:00Z")
          .copy(parentWorkflowId = "wf-parent")
      val firstFinish =
        finishedRecord("wf-parent:seg:1", status = "blocked", startedAt = "2026-06-04T10:00:00Z")
          .copy(parentWorkflowId = "wf-parent", stopReason = "BLOCKED")
      repeat(2) {
        store.goalStarted(firstStart, level = "full")
        store.goalFinished(firstFinish, level = "full")
      }
      store.goalStarted(
        startedRecord("wf-parent:seg:2", subtaskTotal = 1, resumed = true, startedAt = "2026-06-04T10:10:00Z")
          .copy(parentWorkflowId = "wf-parent"),
        level = "full",
      )
      store.goalFinished(
        finishedRecord("wf-parent:seg:2", status = "blocked", startedAt = "2026-06-04T10:10:00Z")
          .copy(parentWorkflowId = "wf-parent", stopReason = "TIMEOUT"),
        level = "full",
      )
      store.goalStarted(
        startedRecord("wf-parent:seg:3", subtaskTotal = 1, resumed = true, startedAt = "2026-06-04T10:20:00Z")
          .copy(parentWorkflowId = "wf-parent"),
        level = "full",
      )

      val issueFinished =
        GoalIssueFinishedRecord(
          issueKey = "SKILL-66",
          parentWorkflowId = "wf-parent",
          status = "completed",
          subtasksComplete = 1,
          subtasksBlocked = 0,
          subtasksSkipped = 0,
          finishedAt = "2026-06-04T10:30:00Z",
          mode = "runtime",
        )
      store.goalIssueFinished(issueFinished, level = "full")
      store.goalIssueFinished(issueFinished, level = "full")

      val outbox = pendingOutbox(connection)
      assertEquals(1, outbox.count { it.eventName == "skillbill_goal_issue_finished" })
      val payload = parsePayload(outbox.single { it.eventName == "skillbill_goal_issue_finished" }.payloadJson)
      assertEquals("wf-parent", payload["parent_workflow_id"])
      assertEquals("completed", payload["status"])
      assertEquals(3, payload["total_invocations"])
      assertEquals(2, payload["total_blocks"])
      assertEquals(2, payload["total_resumes"])
      assertEquals("2026-06-04T10:00:00Z", payload["first_started_at"])
      assertEquals(1_800L, assertIs<Number>(payload["duration_seconds"]).toLong())
      assertTrue("duration_ms" !in payload)
    }
  }

  private fun seedBlockedRunWithMixedSubtasks(store: LifecycleTelemetryStore) {
    store.goalStarted(startedRecord("wf-1", subtaskTotal = 3, resumed = false), level = "full")
    store.goalSubtaskFinished(subtask(id = 1, status = "complete", durationMs = 60_000, attempts = 1), "full")
    store.goalSubtaskFinished(
      subtask(id = 2, status = "blocked", durationMs = 120_000, attempts = 2, blockedReason = "validation failed"),
      "full",
    )
    store.goalSubtaskFinished(subtask(id = 3, status = "skipped", durationMs = 0, attempts = 0), "full")
    store.goalFinished(
      GoalFinishedRecord(
        issueKey = "SKILL-66",
        workflowId = "wf-1",
        status = "blocked",
        startedAt = "2026-06-04T10:00:00Z",
        finishedAt = "2026-06-04T10:30:00Z",
        durationMs = 1_800_000,
        subtasksComplete = 1,
        subtasksBlocked = 1,
        subtasksSkipped = 1,
        mode = "runtime",
      ),
      level = "full",
    )
  }

  private fun assertOutboxEmittedOncePerEvent(connection: Connection) {
    val outbox = pendingOutbox(connection)
    assertEquals(1, outbox.count { it.eventName == "skillbill_goal_started" })
    assertEquals(
      "running",
      parsePayload(outbox.single { it.eventName == "skillbill_goal_started" }.payloadJson)["status"],
    )
    assertEquals(
      "runtime",
      parsePayload(outbox.single { it.eventName == "skillbill_goal_started" }.payloadJson)["mode"],
    )
    assertEquals(3, outbox.count { it.eventName == "skillbill_goal_subtask_finished" })
    assertEquals(1, outbox.count { it.eventName == "skillbill_goal_finished" })
    assertEquals(
      "runtime",
      parsePayload(outbox.single { it.eventName == "skillbill_goal_finished" }.payloadJson)["mode"],
    )
    assertTrue(
      outbox.any { it.eventName == "skillbill_goal_subtask_finished" && it.payloadJson.contains("validation failed") },
      "Blocked subtask outbox payload should carry blocked_reason at full level.",
    )
  }

  private fun assertGoalTerminalDurationPayloadsUseSeconds(connection: Connection) {
    val payloads =
      pendingOutbox(connection)
        .filter { it.eventName in setOf("skillbill_goal_subtask_finished", "skillbill_goal_finished") }
        .map { parsePayload(it.payloadJson) }

    payloads.forEach { payload ->
      assertTrue("duration_seconds" in payload)
      assertTrue("duration_ms" !in payload)
    }
    assertEquals(60L, assertIs<Number>(payloads.first { it["subtask_id"] == 1 }["duration_seconds"]).toLong())
    assertEquals(1_800L, assertIs<Number>(payloads.single { "subtask_id" !in it }["duration_seconds"]).toLong())
  }

  private fun finishedRun(
    store: LifecycleTelemetryStore,
    workflowId: String,
    startedAt: String,
    status: String,
    durationMs: Long,
  ) {
    store.goalStarted(startedRecord(workflowId, subtaskTotal = 1, resumed = false, startedAt = startedAt), "full")
    store.goalFinished(
      GoalFinishedRecord(
        issueKey = "SKILL-66",
        workflowId = workflowId,
        status = status,
        startedAt = startedAt,
        finishedAt = "2026-06-05T00:00:00Z",
        durationMs = durationMs,
        subtasksComplete = 1,
        subtasksBlocked = 0,
        subtasksSkipped = 0,
        mode = "runtime",
      ),
      level = "full",
    )
  }

  private fun finishedRecord(
    workflowId: String,
    status: String,
    startedAt: String,
  ): GoalFinishedRecord =
    GoalFinishedRecord(
      issueKey = "SKILL-66",
      workflowId = workflowId,
      status = status,
      startedAt = startedAt,
      finishedAt = "2026-06-04T10:05:00Z",
      durationMs = 300_000,
      subtasksComplete = if (status == "completed") 1 else 0,
      subtasksBlocked = if (status == "blocked") 1 else 0,
      subtasksSkipped = 0,
      mode = "runtime",
    )

  private fun startedRecord(
    workflowId: String,
    subtaskTotal: Int,
    resumed: Boolean,
    startedAt: String = "2026-06-04T10:00:00Z",
    mode: String = "runtime",
  ): GoalStartedRecord =
    GoalStartedRecord(
      issueKey = "SKILL-66",
      featureName = "goal telemetry",
      workflowId = workflowId,
      subtaskTotal = subtaskTotal,
      resumed = resumed,
      startedAt = startedAt,
      status = "running",
      mode = mode,
    )

  private fun subtask(
    id: Int,
    status: String,
    durationMs: Long,
    attempts: Int,
    blockedReason: String? = null,
  ): GoalSubtaskFinishedRecord =
    GoalSubtaskFinishedRecord(
      issueKey = "SKILL-66",
      workflowId = "wf-1",
      subtaskId = id,
      subtaskName = "subtask-$id",
      status = status,
      startedAt = "2026-06-04T10:00:00Z",
      finishedAt = "2026-06-04T10:05:00Z",
      durationMs = durationMs,
      attemptCount = attempts,
      blockedReason = blockedReason,
    )

  private fun attributedSubtask(
    id: Int,
    finalizingAgentId: String,
    participatingAgentIds: List<String>,
  ): GoalSubtaskFinishedRecord =
    subtask(id, "complete", 60_000, 1).copy(
      finalizingAgentId = finalizingAgentId,
      participatingAgentIds = participatingAgentIds,
    )

  private fun parsePayload(payloadJson: String): Map<String, Any?> {
    val element = requireNotNull(JsonCodec.parseObjectOrNull(payloadJson))
    return requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(element)))
  }

  private fun pendingOutbox(connection: Connection): List<TelemetryOutboxRecord> =
    TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(limit = null)

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("runtime-kotlin-goal-telemetry").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }
}
