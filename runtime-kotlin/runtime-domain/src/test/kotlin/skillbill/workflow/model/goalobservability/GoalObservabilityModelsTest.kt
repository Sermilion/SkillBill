package skillbill.workflow.model.goalobservability

import skillbill.error.shellcontent.InvalidGoalObservabilityEventSchemaError
import skillbill.error.shellcontent.InvalidGoalProgressEventSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.GoalObservabilityArtifacts
import skillbill.goalrunner.goalObservabilityHistory
import skillbill.goalrunner.goalObservabilityLatestEvent
import skillbill.goalrunner.goalProgressHistory
import skillbill.goalrunner.goalProgressLatestEvent
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalObservabilityProgressInput
import skillbill.goalrunner.model.GoalObservabilityRuntimeEventInput
import skillbill.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY
import skillbill.workflow.engine.model.GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GoalObservabilityModelsTest {
  @Test
  fun `history append preserves sequence order and prunes oldest entries`() {
    val history =
      (1..(GOAL_OBSERVABILITY_HISTORY_LIMIT + 2))
        .fold(GoalObservabilityHistory()) { current, sequence ->
          current.append(event(sequence))
        }

    assertEquals(GOAL_OBSERVABILITY_HISTORY_LIMIT, history.events.size)
    assertEquals(3, history.events.first().sequenceNumber)
    assertEquals(GOAL_OBSERVABILITY_HISTORY_LIMIT + 2, history.events.last().sequenceNumber)
  }

  @Test
  fun `unknown goal progress event kind wire token fails typed workflow error`() {
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressEventKind.fromWire("not-an-event-kind")
    }
  }

  @Test
  fun `unknown goal observability and ledger tokens fail with typed workflow errors`() {
    assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      GoalObservabilityRecordKind.fromWire("not-a-record-kind")
    }
    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      GoalProgressOutcome.fromWire("not-an-outcome")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      GoalAttemptLedgerAction.fromWire("not-an-action")
    }
  }

  @Test
  fun `goal observability decoder rejects malformed list elements with a typed error`() {
    val malformed =
      event(1)
        .toArtifactMap(includeHeavyFields = true)
        .toMutableMap()
        .apply { this["changed_files"] = listOf(7) }

    assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      goalObservabilityEventFromArtifact(
        raw = malformed,
        sourceLabel = "goal_observability_latest_event",
      )
    }
  }

  @Test
  fun `goal observability progress rejects fractional durable sequence numbers`() {
    assertFailsWith<InvalidGoalObservabilityEventSchemaError> {
      GoalObservabilityArtifacts.patchForProgressEvent(
        GoalObservabilityProgressInput(
          artifacts =
            FeatureTaskRuntimeWorkflowArtifactMap.from(
              mapOf(
                "goal_continuation" to mapOf("issue_key" to "SKILL-372", "subtask_id" to 1),
                "progress_event" to mapOf("timestamp" to "2026-06-01T00:00:00Z", "sequence" to 2.7),
              ),
            ),
          workflowId = "wf-372",
          workflowStatus = "running",
          currentStepId = "implement",
        ),
      )
    }
  }

  @Test
  fun `observability history allocates distinct rising sequences across runtime and child patches`() {
    val first = runtimeEventPatch(emptyMap<String, Any?>(), "2026-06-01T00:00:00Z")
    val second =
      GoalObservabilityArtifacts.patchForProgressEvent(
        GoalObservabilityProgressInput(
          artifacts =
            FeatureTaskRuntimeWorkflowArtifactMap.from(
              first +
                mapOf(
                  "goal_continuation" to mapOf("issue_key" to "SKILL-378", "subtask_id" to 1),
                  "progress_event" to mapOf("timestamp" to "2026-06-01T00:01:00Z", "sequence" to 0),
                ),
            ),
          workflowId = "wf-378",
          workflowStatus = "running",
          currentStepId = "implement",
        ),
      ).let(::asArtifacts)
    val third = runtimeEventPatch(second, "2026-06-01T00:02:00Z")

    val sequences =
      (third[GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY] as List<*>)
        .map { entry -> (entry as Map<*, *>)["sequence_number"] }

    assertEquals(listOf(0, 1, 2), sequences)
  }

  private fun runtimeEventPatch(
    artifacts: Any,
    timestamp: String,
  ): Map<String, Any?> =
    asArtifacts(
      GoalObservabilityArtifacts.patchForRuntimeEvent(
        GoalObservabilityRuntimeEventInput(
          artifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(artifacts),
          request =
            GoalRunnerObservabilityRecordRequest(
              workflowId = "wf-378",
              issueKey = "SKILL-378",
              subtaskId = 1,
              workflowPhase = "implement",
              workerRole = "goal_runner",
              livenessClass = "phase_change",
              activitySummary = "goal runner advanced the child workflow.",
              timestamp = timestamp,
            ),
        ),
      ),
    )

  private fun asArtifacts(value: Any?): Map<String, Any?> =
    (value as Map<*, *>).entries.associate { (key, entry) -> key.toString() to entry }

  @Test
  fun `default artifact rendering omits optional heavy fields`() {
    val rendered = event(1, changedFiles = listOf("src/Main.kt")).toArtifactMap()

    assertFalse(rendered.containsKey("changed_files"))
    assertEquals("implement", rendered["workflow_phase"])
    assertEquals("durable_progress", rendered["liveness_class"])
  }

  @Test
  fun `goal observability timestamps decode to instants`() {
    val decoded =
      goalObservabilityEventFromArtifact(
        raw = event(1).toArtifactMap(),
        sourceLabel = "goal_observability_latest_event",
      )

    assertEquals(Instant.parse("2026-06-01T00:00:00Z"), decoded.timestamp)
  }

  @Test
  fun `goal observability accessors preserve latest event and bounded history`() {
    val latest = event(2).toArtifactMap()
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          GOAL_OBSERVABILITY_LATEST_EVENT_ARTIFACT_KEY to latest,
          GOAL_OBSERVABILITY_RUN_HISTORY_ARTIFACT_KEY to listOf(event(1).toArtifactMap(), latest),
        ),
      )

    assertEquals(event(2), artifacts.goalObservabilityLatestEvent())
    assertEquals(listOf(event(1), event(2)), artifacts.goalObservabilityHistory().events)
  }

  @Test
  fun `goal progress accessors preserve latest event and history`() {
    val progress =
      GoalProgressEvent(
        eventKind = GoalProgressEventKind.PHASE_COMPLETED,
        workflowId = "wf-372",
        workflowPhase = "implement",
        processAlive = false,
        sequenceNumber = 2,
        timestamp = "2026-06-01T00:00:00Z",
      )
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        mapOf(
          GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY to progress.toPersistenceWire(),
          GOAL_PROGRESS_RUN_HISTORY_ARTIFACT_KEY to listOf(progress.toPersistenceWire()),
        ),
      )

    assertEquals(progress, artifacts.goalProgressLatestEvent())
    assertEquals(listOf(progress), artifacts.goalProgressHistory())
  }

  private fun event(
    sequence: Int,
    changedFiles: List<String> = emptyList(),
  ): GoalObservabilityEvent =
    GoalObservabilityEvent(
      issueKey = "SKILL-61",
      subtaskId = 1,
      workflowPhase = "implement",
      workerRole = "phase_subagent",
      livenessClass = "durable_progress",
      activitySummary = "working",
      timestamp = "2026-06-01T00:00:00Z",
      sequenceNumber = sequence,
      changedFiles = changedFiles,
    )
}
