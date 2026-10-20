package skillbill.goalrunner

import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalProgressCurrentStepEnteredAtTest {
  @Test
  fun `a step change counts from the first heartbeat of the new step`() {
    val history =
      listOf(
        heartbeat(1, "qa", "2026-10-20T07:42:00Z"),
        heartbeat(2, "qa", "2026-10-20T09:50:00Z"),
        heartbeat(3, "qa_fix", "2026-10-20T10:03:49Z"),
        heartbeat(4, "qa_fix", "2026-10-20T10:05:19Z"),
      )

    assertEquals(Instant.parse("2026-10-20T10:03:49Z"), currentStepEnteredAt(history))
  }

  @Test
  fun `history that never leaves one step gives no transition`() {
    val history =
      listOf(
        heartbeat(1, "qa", "2026-10-20T07:42:00Z"),
        heartbeat(2, "qa", "2026-10-20T09:50:00Z"),
      )

    assertNull(currentStepEnteredAt(history))
  }

  private fun heartbeat(
    sequence: Int,
    step: String,
    at: String,
  ): GoalProgressEvent =
    GoalProgressEvent(
      eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
      workflowId = "wftr-goal",
      workflowPhase = step,
      processAlive = true,
      sequenceNumber = sequence,
      timestamp = Instant.parse(at),
      stepId = step,
      operationName = "The '$step' child is active; source progress is not confirmed.",
    )
}
