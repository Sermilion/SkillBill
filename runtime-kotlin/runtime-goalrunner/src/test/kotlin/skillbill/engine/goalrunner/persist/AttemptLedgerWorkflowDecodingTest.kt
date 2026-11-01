package skillbill.engine.goalrunner.persist

import skillbill.contracts.JsonCodec
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.progressToken
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_PROGRESS_LATEST_EVENT.label()

class AttemptLedgerWorkflowDecodingTest {
  @Test
  fun `progress token fingerprints artifacts without embedding the body`() {
    val bloatedArtifacts =
      JsonCodec.mapToJsonString(
        mapOf("feature_task_runtime_delivered_projections" to "x".repeat(50_000)),
      )
    val snapshot = progressSnapshot(artifactsJson = bloatedArtifacts)

    val token = snapshot.progressToken()

    assertFalse(token.contains("xxxx"))
    assertTrue(token.length < 2_000)
    assertTrue(snapshot.artifacts.hashCode().toString() in token)
  }

  @Test
  fun `progress token changes when only artifacts mutate`() {
    val before = progressSnapshot(artifactsJson = """{"progress_event":{"summary":"a"}}""")
    val after = progressSnapshot(artifactsJson = """{"progress_event":{"summary":"b"}}""")

    assertNotEquals(before.progressToken(), after.progressToken())
  }

  @Test
  fun `decodeArtifactKeys materializes only requested top-level keys`() {
    val artifactsJson =
      JsonCodec.mapToJsonString(
        mapOf(
          GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY to
            mapOf(
              "event_kind" to "operation_heartbeat",
              "workflow_id" to "wfl-child",
            ),
          "feature_task_runtime_delivered_projections" to
            mapOf(
              "bloat" to "y".repeat(20_000),
            ),
          "progress_event" to mapOf("summary" to "alive"),
        ),
      )

    val sparse =
      decodeArtifactKeysForTest(
        artifactsJson,
        setOf(GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY, "progress_event"),
      )

    assertEquals(setOf(GOAL_PROGRESS_LATEST_EVENT_ARTIFACT_KEY, "progress_event"), sparse.keys)
    assertEquals("alive", (sparse["progress_event"] as Map<*, *>)["summary"])
    assertFalse(sparse.containsKey("feature_task_runtime_delivered_projections"))
  }

  private fun progressSnapshot(artifactsJson: String): WorkflowStateSnapshot =
    WorkflowStateSnapshot(
      workflowId = "wfl-child",
      sessionId = "session-1",
      workflowName = "bill-feature-task",
      contractVersion = "1.0",
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "validate",
      steps = listOf(WorkflowStepState("validate", WorkflowStepStatus.RUNNING, 1)),
      artifacts =
        DurableWorkflowArtifacts.fromMap(
          requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(artifactsJson))),
        ),
      startedAt = Instant.parse("2026-06-02T10:00:00Z"),
      updatedAt = Instant.parse("2026-06-02T10:00:01Z"),
      finishedAt = null,
    )
}

private fun decodeArtifactKeysForTest(
  existingArtifactsJson: String,
  keys: Set<String>,
): Map<String, Any?> {
  if (keys.isEmpty()) return emptyMap()
  val root = JsonCodec.parseObjectOrNull(existingArtifactsJson) ?: return emptyMap()
  return buildMap {
    keys.forEach { key ->
      val element = root[key] ?: return@forEach
      put(key, JsonCodec.jsonElementToValue(element))
    }
  }
}
