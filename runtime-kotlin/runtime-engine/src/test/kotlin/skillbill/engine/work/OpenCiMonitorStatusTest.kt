package skillbill.engine.work

import skillbill.goalrunner.model.GoalRunnerStatusProjection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenCiMonitorStatusTest {
  @Test
  fun `finished subtasks with no goal completion still owe ci monitor`() {
    assertTrue(goalStaysOnOpenCiMonitor(projection(complete = 1, pending = 0, blocked = 0), false))
  }

  @Test
  fun `an unfinished subtask does not owe ci monitor`() {
    assertFalse(goalStaysOnOpenCiMonitor(projection(complete = 1, pending = 2, blocked = 0), false))
  }

  @Test
  fun `a recorded goal completion does not owe ci monitor`() {
    assertFalse(goalStaysOnOpenCiMonitor(projection(complete = 1, pending = 0, blocked = 0), true))
  }

  @Test
  fun `a paused goal does not stay on ci monitor`() {
    assertFalse(
      goalStaysOnOpenCiMonitor(projection(complete = 1, pending = 0, blocked = 0, paused = true), false),
    )
  }

  private fun projection(
    complete: Int,
    pending: Int,
    blocked: Int,
    paused: Boolean = false,
  ): GoalRunnerStatusProjection =
    GoalRunnerStatusProjection(
      issueKey = "SKILL-415",
      completeCount = complete,
      pendingCount = pending,
      blockedCount = blocked,
      currentSubtaskId = null,
      currentStep = null,
      activeAgent = null,
      paused = paused,
    )
}
