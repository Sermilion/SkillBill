package skillbill.engine.work

import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `open ci monitor keeps monitor over a completed child phase id`() {
    val step =
      goalCurrentStep(
        null,
        "commit_push",
        "monitor",
        IdeStatusLifecycleState.ACTIVE,
        GoalCurrentStepSignals(openCiMonitor = true, liveFinalizationStep = false),
      )
    assertEquals("monitor", step.id)
    assertEquals("monitor", step.label)
  }

  @Test
  fun `live finalization keeps monitor over a completed child phase id`() {
    val step =
      goalCurrentStep(
        null,
        "commit_push",
        "monitor",
        IdeStatusLifecycleState.ACTIVE,
        GoalCurrentStepSignals(openCiMonitor = false, liveFinalizationStep = true),
      )
    assertEquals("monitor", step.id)
    assertEquals("monitor", step.label)
  }

  @Test
  fun `a live goal on monitor projects finalization when children are complete`() {
    assertTrue(
      goalProjectsLiveFinalizationStep(
        projection(complete = 1, pending = 0, blocked = 0)
          .copy(executionLiveness = ExecutionLiveness.LIVE, currentStep = "monitor"),
        false,
      ),
    )
  }

  @Test
  fun `a live goal on implement does not project finalization`() {
    assertFalse(
      goalProjectsLiveFinalizationStep(
        projection(complete = 0, pending = 1, blocked = 0)
          .copy(executionLiveness = ExecutionLiveness.LIVE, currentStep = "implement"),
        false,
      ),
    )
  }

  @Test
  fun `a paused goal does not stay on ci monitor`() {
    assertFalse(
      goalStaysOnOpenCiMonitor(projection(complete = 1, pending = 0, blocked = 0).copy(paused = true), false),
    )
  }

  private fun projection(
    complete: Int,
    pending: Int,
    blocked: Int,
  ): GoalRunnerStatusProjection =
    GoalRunnerStatusProjection(
      issueKey = "SKILL-415",
      completeCount = complete,
      pendingCount = pending,
      blockedCount = blocked,
      currentSubtaskId = null,
      currentStep = null,
      activeAgent = null,
      executionLiveness = ExecutionLiveness.UNKNOWN,
      paused = false,
    )
}
