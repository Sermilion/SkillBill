package skillbill.infrastructure.launcher.process.waitloop

import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeclaredProgressTrackerTest {
  @Test
  fun `phase started resets the wall-clock origin`() {
    val tracker = DeclaredProgressTracker(startNanos = 0L)

    assertTrue(
      tracker.observe(
        snapshot(
          kind = GoalProgressEventKind.PHASE_STARTED,
          phase = "implement",
          sequence = 0,
        ),
        nowNanos = 10L,
      ),
    )
  }

  @Test
  fun `phase completed is a phase transition`() {
    val tracker = DeclaredProgressTracker(startNanos = 0L)
    tracker.observe(
      snapshot(
        kind = GoalProgressEventKind.PHASE_STARTED,
        phase = "implement",
        sequence = 0,
      ),
      nowNanos = 10L,
    )

    assertTrue(
      tracker.observe(
        snapshot(
          kind = GoalProgressEventKind.PHASE_COMPLETED,
          phase = "implement",
          sequence = 1,
        ),
        nowNanos = 20L,
      ),
    )
  }

  @Test
  fun `workflow phase change is a phase transition`() {
    val tracker = DeclaredProgressTracker(startNanos = 0L)
    tracker.observe(
      snapshot(
        kind = GoalProgressEventKind.OPERATION_STARTED,
        phase = "implement",
        sequence = 0,
        operationName = "write",
      ),
      nowNanos = 10L,
    )

    assertTrue(
      tracker.observe(
        snapshot(
          kind = GoalProgressEventKind.OPERATION_STARTED,
          phase = "validate",
          sequence = 1,
          operationName = "check",
        ),
        nowNanos = 20L,
      ),
    )
  }

  @Test
  fun `same-phase operation heartbeat is not a phase transition`() {
    val tracker = DeclaredProgressTracker(startNanos = 0L)
    tracker.observe(
      snapshot(
        kind = GoalProgressEventKind.OPERATION_STARTED,
        phase = "validate",
        sequence = 0,
        operationName = "check",
      ),
      nowNanos = 10L,
    )

    assertFalse(
      tracker.observe(
        snapshot(
          kind = GoalProgressEventKind.OPERATION_HEARTBEAT,
          phase = "validate",
          sequence = 1,
          operationName = "check",
        ),
        nowNanos = 20L,
      ),
    )
  }

  @Test
  fun `first operation is not a phase transition`() {
    val tracker = DeclaredProgressTracker(startNanos = 0L)

    assertFalse(
      tracker.observe(
        snapshot(
          kind = GoalProgressEventKind.OPERATION_STARTED,
          phase = "implement",
          sequence = 0,
          operationName = "write",
        ),
        nowNanos = 10L,
      ),
    )
  }
}

private fun snapshot(
  kind: GoalProgressEventKind,
  phase: String,
  sequence: Int,
  operationName: String? = null,
): AgentRunDeclaredProgressSnapshot =
  AgentRunDeclaredProgressSnapshot(
    latestEvent =
      GoalProgressEvent(
        eventKind = kind,
        workflowId = "wfl-child",
        workflowPhase = phase,
        processAlive = true,
        sequenceNumber = sequence,
        timestamp = "2026-06-02T10:00:00Z",
        stepId = phase,
        operationName = operationName,
        expectedLong = kind.isOperationEvent,
      ),
    processAlive = true,
  )
