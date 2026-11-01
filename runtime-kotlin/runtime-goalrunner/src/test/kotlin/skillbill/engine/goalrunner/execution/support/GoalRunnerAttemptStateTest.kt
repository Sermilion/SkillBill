package skillbill.engine.goalrunner.execution.support

import kotlin.test.Test
import kotlin.test.assertEquals

class GoalRunnerAttemptStateTest {
  @Test
  fun `attempt snapshots preserve launch order without exposing mutable storage`() {
    val state = GoalRunnerAttemptState()

    state.record(7)
    val firstSnapshot = state.attempted
    state.record(3)

    assertEquals(listOf(7), firstSnapshot)
    assertEquals(listOf(7, 3), state.attempted)
  }
}
