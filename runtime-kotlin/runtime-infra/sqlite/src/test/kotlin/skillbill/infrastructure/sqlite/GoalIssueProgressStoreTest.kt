package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.telemetry.goal.recordGoalIssueSegmentEnd
import skillbill.infrastructure.sqlite.telemetry.lifecycle.LifecycleTelemetryStore
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoalIssueProgressStoreTest {
  @Test
  fun `goal issue progress state entry metadata changes only on real status transitions`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val parentWorkflowId = "wf-state-parent"
      val initial = startedRecord(parentWorkflowId, "seg:1", resumed = false, startedAt = "2026-06-04T10:00:00Z")
      store.goalStarted(initial, "full")
      assertGoalIssueState(connection, parentWorkflowId, "running", initial.startedAt)

      store.goalStarted(initial.copy(workflowId = "$parentWorkflowId:seg:2", resumed = true), "full")
      assertGoalIssueState(connection, parentWorkflowId, "running", initial.startedAt)

      val blocked = finishBlockedSegment(connection, store, parentWorkflowId)
      assertUnchangedAfterRepeatedBlockedSegment(connection, parentWorkflowId, blocked)

      val resumed = resumeGoalIssue(connection, store, initial, parentWorkflowId)
      val completed = completeGoalIssue(connection, store, parentWorkflowId)
      assertTerminalTransition(resumed, completed)
      assertCompletionIsIdempotent(connection, store, parentWorkflowId, completed)
    }
  }

  @Test
  fun `delayed blocked segments cannot replace a terminal goal issue state`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val parentWorkflowId = "wf-terminal-parent"
      store.goalStarted(startedRecord(parentWorkflowId, "seg:1", resumed = false), "full")
      val completed = goalIssueFinishedRecord(parentWorkflowId)
      store.goalIssueFinished(completed, "full")

      val terminal = goalIssueState(connection, parentWorkflowId)
      recordGoalIssueSegmentEnd(connection, parentWorkflowId, "SKILL-66", "$parentWorkflowId:seg:1", "blocked")
      assertEquals(terminal, goalIssueState(connection, parentWorkflowId))
    }
  }

  @Test
  fun `late goal segment starts cannot replace a terminal goal issue state`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val parentWorkflowId = "wf-terminal-start-parent"
      val firstSegment = startedRecord(parentWorkflowId, "seg:1", resumed = false)
      store.goalStarted(firstSegment, "full")
      store.goalIssueFinished(goalIssueFinishedRecord(parentWorkflowId), "full")

      val terminal = goalIssueState(connection, parentWorkflowId)
      store.goalStarted(firstSegment.copy(workflowId = "$parentWorkflowId:seg:late", resumed = true), "full")
      assertEquals(terminal, goalIssueState(connection, parentWorkflowId))
    }
  }

  @Test
  fun `goal issue completion recovers aggregates from persisted segments when progress is missing`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val firstStart = startedRecord("wf-recover", "seg:1", resumed = false, startedAt = "2026-06-04T10:00:00Z")
      val secondStart = startedRecord("wf-recover", "seg:2", resumed = true, startedAt = "2026-06-04T10:10:00Z")
      store.goalStarted(firstStart, "full")
      store.goalFinished(finishedRecord(firstStart.workflowId, "blocked", firstStart.startedAt), "full")
      store.goalStarted(secondStart, "full")
      connection.createStatement().use { it.executeUpdate("DELETE FROM goal_issue_progress") }

      store.goalIssueFinished(goalIssueFinishedRecord("wf-recover"), "full")
      val payload = terminalPayload(connection)
      assertEquals(2, payload["total_invocations"])
      assertEquals(1, payload["total_resumes"])
      assertEquals(1, payload["total_blocks"])
      assertEquals("2026-06-04T10:00:00Z", payload["first_started_at"])
    }
  }

  @Test
  fun `goal issue completion without trustworthy history suppresses terminal emission`() {
    withConnection { connection ->
      LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics).goalIssueFinished(
        goalIssueFinishedRecord("wf-missing").copy(issueKey = "SKILL-NO-HISTORY"),
        "full",
      )
      assertEquals(0, pendingOutbox(connection).count { it.eventName == "skillbill_goal_issue_finished" })
    }
  }

  @Test
  fun `paused goal segments record a resumable status without counting a block`() {
    withConnection { connection ->
      val store = LifecycleTelemetryStore(connection, "test-runtime-version", SqliteTestDiagnostics)
      val parentWorkflowId = "wf-paused-parent"
      store.goalStarted(startedRecord(parentWorkflowId, "seg:1", resumed = false), "full")

      store.goalFinished(finishedRecord("$parentWorkflowId:seg:1", "paused", "2026-06-04T10:10:00Z"), "full")

      assertEquals("paused", goalIssueState(connection, parentWorkflowId).status)
      assertEquals(0, totalBlocks(connection, parentWorkflowId))
    }
  }

  private fun totalBlocks(
    connection: Connection,
    parentWorkflowId: String,
  ): Int =
    connection.prepareStatement(
      "SELECT total_blocks FROM goal_issue_progress WHERE parent_workflow_id = ? AND issue_key = ?",
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.setString(2, "SKILL-66")
      statement.executeQuery().use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }

  private fun finishBlockedSegment(
    connection: Connection,
    store: LifecycleTelemetryStore,
    parentWorkflowId: String,
  ): GoalIssueState {
    store.goalFinished(finishedRecord("$parentWorkflowId:seg:2", "blocked", "2026-06-04T10:10:00Z"), "full")
    return goalIssueState(connection, parentWorkflowId).also { state ->
      assertEquals("blocked", state.status)
      assertFalse(state.estimated)
    }
  }

  private fun assertUnchangedAfterRepeatedBlockedSegment(
    connection: Connection,
    parentWorkflowId: String,
    blocked: GoalIssueState,
  ) {
    recordGoalIssueSegmentEnd(connection, parentWorkflowId, "SKILL-66", "$parentWorkflowId:seg:2-repeat", "blocked")
    assertEquals(blocked, goalIssueState(connection, parentWorkflowId))
  }

  private fun resumeGoalIssue(
    connection: Connection,
    store: LifecycleTelemetryStore,
    initial: GoalStartedRecord,
    parentWorkflowId: String,
  ): GoalIssueState {
    store.goalStarted(initial.copy(workflowId = "$parentWorkflowId:seg:3", resumed = true), "full")
    return goalIssueState(connection, parentWorkflowId).also { state ->
      assertEquals("running", state.status)
      assertFalse(state.estimated)
    }
  }

  private fun completeGoalIssue(
    connection: Connection,
    store: LifecycleTelemetryStore,
    parentWorkflowId: String,
  ): GoalIssueState {
    store.goalIssueFinished(goalIssueFinishedRecord(parentWorkflowId), "full")
    return goalIssueState(connection, parentWorkflowId).also { state ->
      assertEquals("completed", state.status)
      assertFalse(state.estimated)
    }
  }

  private fun assertTerminalTransition(
    resumed: GoalIssueState,
    completed: GoalIssueState,
  ) {
    assertTrue(Instant.parse(completed.enteredAt).isAfter(Instant.parse(resumed.enteredAt)))
  }

  private fun assertCompletionIsIdempotent(
    connection: Connection,
    store: LifecycleTelemetryStore,
    parentWorkflowId: String,
    completed: GoalIssueState,
  ) {
    store.goalIssueFinished(goalIssueFinishedRecord(parentWorkflowId), "full")
    assertEquals(completed, goalIssueState(connection, parentWorkflowId))
  }

  private fun startedRecord(
    parentWorkflowId: String,
    segment: String,
    resumed: Boolean,
    startedAt: String = "2026-06-04T10:00:00Z",
  ): GoalStartedRecord =
    GoalStartedRecord(
      issueKey = "SKILL-66",
      featureName = "goal telemetry",
      workflowId = "$parentWorkflowId:$segment",
      subtaskTotal = 1,
      resumed = resumed,
      startedAt = startedAt,
      status = "running",
      mode = "runtime",
      parentWorkflowId = parentWorkflowId,
    )

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
      subtasksComplete = 0,
      subtasksBlocked = 1,
      subtasksSkipped = 0,
      mode = "runtime",
      parentWorkflowId = workflowId.substringBefore(":seg:"),
      stopReason = "POLICY_BLOCKED",
    )

  private fun goalIssueFinishedRecord(parentWorkflowId: String): GoalIssueFinishedRecord =
    GoalIssueFinishedRecord(
      issueKey = "SKILL-66",
      parentWorkflowId = parentWorkflowId,
      status = "completed",
      subtasksComplete = 1,
      subtasksBlocked = 0,
      subtasksSkipped = 0,
      finishedAt = "2026-06-04T10:30:00Z",
      mode = "runtime",
    )

  private fun terminalPayload(connection: Connection): Map<String, Any?> {
    val payloadJson = pendingOutbox(connection).single { it.eventName == "skillbill_goal_issue_finished" }.payloadJson
    val element = requireNotNull(JsonCodec.parseObjectOrNull(payloadJson))
    return requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(element)))
  }

  private fun pendingOutbox(connection: Connection): List<TelemetryOutboxRecord> =
    TelemetryOutboxStore(connection, version = "test-runtime-version").listPending(limit = null)

  private fun assertGoalIssueState(
    connection: Connection,
    parentWorkflowId: String,
    status: String,
    enteredAt: String,
  ) {
    assertEquals(GoalIssueState(status, enteredAt, estimated = false), goalIssueState(connection, parentWorkflowId))
  }

  private fun goalIssueState(
    connection: Connection,
    parentWorkflowId: String,
  ): GoalIssueState =
    connection.prepareStatement(
      "SELECT status, state_entered_at, state_entered_at_estimated " +
        "FROM goal_issue_progress WHERE parent_workflow_id = ?",
    ).use { statement ->
      statement.setString(1, parentWorkflowId)
      statement.executeQuery().use { resultSet ->
        check(resultSet.next()) { "Expected goal issue progress for $parentWorkflowId." }
        GoalIssueState(
          status = resultSet.getString("status"),
          enteredAt = resultSet.getString("state_entered_at"),
          estimated = resultSet.getInt("state_entered_at_estimated") != 0,
        )
      }
    }

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("runtime-kotlin-goal-telemetry").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }

  private data class GoalIssueState(val status: String, val enteredAt: String, val estimated: Boolean)
}
