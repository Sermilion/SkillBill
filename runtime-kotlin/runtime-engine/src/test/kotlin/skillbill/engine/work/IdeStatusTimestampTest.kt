package skillbill.engine.work

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.status.IDE_STATUS_CONTRACT_VERSION
import skillbill.engine.work.model.toStatusWireMap
import skillbill.ports.idestatus.model.IdeStatusCurrentSubtask
import skillbill.ports.idestatus.model.IdeStatusFreshness
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class IdeStatusTimestampTest {
  @Test
  fun `started_at stays stable across repeated wire maps`() {
    val started = Instant.parse("2026-08-06T08:00:00Z")
    val first = snapshot(startedAt = started).toStatusWireMap()
    val second = snapshot(startedAt = started).toStatusWireMap()
    assertEquals(first["started_at"], second["started_at"])
    assertEquals("2026-08-06T08:00:00Z", first["started_at"])
  }

  @Test
  fun `legacy missing start timestamps omit started_at and subtask started_at`() {
    val wire =
      snapshot(
        startedAt = null,
        currentSubtask = IdeStatusCurrentSubtask(id = "1", startedAt = null),
      ).toStatusWireMap()
    assertFalse(wire.containsKey("started_at"))
    val subtask = requireNotNull(JsonCodec.anyToStringAnyMap(wire["current_subtask"]))
    assertEquals("1", subtask["id"])
    assertNull(subtask["started_at"])
  }

  @Test
  fun `durable current_subtask started_at is preserved on the wire model`() {
    val subtaskStarted = Instant.parse("2026-08-06T09:15:00Z")
    val wire =
      snapshot(
        startedAt = Instant.parse("2026-08-06T08:00:00Z"),
        currentSubtask = IdeStatusCurrentSubtask(id = "2", startedAt = subtaskStarted),
      ).toStatusWireMap()
    val subtask = requireNotNull(JsonCodec.anyToStringAnyMap(wire["current_subtask"]))
    assertEquals("2", subtask["id"])
    assertEquals("2026-08-06T09:15:00Z", subtask["started_at"])
  }

  @Test
  fun `updated_at is never copied into started_at`() {
    val updated = Instant.parse("2026-08-06T12:00:00Z")
    val wire = snapshot(startedAt = null, updatedAt = updated).toStatusWireMap()
    assertFalse(wire.containsKey("started_at"))
    assertEquals(updated.toString(), wire["updated_at"])
  }

  private fun snapshot(
    startedAt: Instant?,
    updatedAt: Instant = Instant.parse("2026-08-06T12:00:00Z"),
    currentSubtask: IdeStatusCurrentSubtask? = null,
  ): IdeStatusSnapshot =
    IdeStatusSnapshot(
      repositoryIdentity = "repo-root-realpath-v1:/repo",
      issueKey = "SKILL-148",
      workflowId = "goal-1",
      workflowFamily = IdeStatusWorkflowFamily.FEATURE_GOAL,
      lifecycleState = IdeStatusLifecycleState.ACTIVE,
      currentStep = IdeStatusStep(id = "implement", label = "Implement"),
      startedAt = startedAt,
      currentSubtask = currentSubtask,
      updatedAt = updatedAt,
      freshness = IdeStatusFreshness.FRESH,
      summary = "Goal SKILL-148 is active on Implement.",
      contractVersion = IDE_STATUS_CONTRACT_VERSION,
    )
}
