package skillbill.goalrunner.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerControlStateTest {
  @Test
  fun `pending operator request pauses with the operator reason and consumes the request`() {
    val paused = GoalRunnerControlState(pauseRequested = true).pauseAtOperatorBoundary(PAUSED_AT)

    assertEquals(true, paused.paused)
    assertEquals(GOAL_PAUSE_REASON_OPERATOR_REQUEST, paused.pauseReason)
    assertEquals(true, paused.pauseConsumed)
    assertEquals(PAUSED_AT, paused.pausedAt)
  }

  @Test
  fun `reached stop-after target pauses with the stop-after reason when no operator request exists`() {
    val paused =
      GoalRunnerControlState(stopAfterSubtaskId = 2)
        .pauseAtOperatorBoundary(PAUSED_AT, targetReached = true)

    assertEquals(true, paused.paused)
    assertEquals(GOAL_PAUSE_REASON_STOP_AFTER_SUBTASK, paused.pauseReason)
    assertEquals(true, paused.stopAfterConsumed)
    assertEquals(PAUSED_AT, paused.pausedAt)
  }

  @Test
  fun `already paused state keeps its reason and only folds in the stop-after consumption`() {
    val alreadyPaused =
      GoalRunnerControlState(
        stopAfterSubtaskId = 2,
        paused = true,
        pauseReason = GOAL_PAUSE_REASON_OPERATOR_STOP,
        pausedAt = "2026-01-01T00:00:00Z",
      )

    val repaused = alreadyPaused.pauseAtOperatorBoundary(PAUSED_AT, targetReached = true)

    assertEquals(GOAL_PAUSE_REASON_OPERATOR_STOP, repaused.pauseReason)
    assertEquals("2026-01-01T00:00:00Z", repaused.pausedAt)
    assertEquals(true, repaused.stopAfterConsumed)
  }

  @Test
  fun `no request and no reached target leaves the state unchanged`() {
    val state = GoalRunnerControlState(stopAfterSubtaskId = 2)

    assertEquals(state, state.pauseAtOperatorBoundary(PAUSED_AT))
  }

  private companion object {
    const val PAUSED_AT = "2026-02-02T00:00:00Z"
  }
}
