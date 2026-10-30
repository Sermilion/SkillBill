package skillbill.idestatus.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeStatusWorkPolicyControlTest {
  @Test
  fun `a pending pause prevents another pause request`() {
    val pending = IdeStatusPolicy.work(goalFacts(pauseRequested = true))
    assertTrue(pending.controlEligible)
    assertFalse(pending.pauseEligible)

    val idle = IdeStatusPolicy.work(goalFacts(pauseRequested = false))
    assertTrue(idle.controlEligible)
    assertTrue(idle.pauseEligible)
  }

  @Test
  fun `an ineligible goal is never pause eligible`() {
    assertFalse(IdeStatusPolicy.work(goalFacts(freshness = IdeStatusFreshness.STALE)).pauseEligible)
    assertFalse(IdeStatusPolicy.work(goalFacts(controlTargetPresent = false)).pauseEligible)
  }

  @Test
  fun `a plan the goal command started is grouped with goals without goal controls`() {
    val facts =
      goalFacts().copy(
        family = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
        scope = IdeStatusExecutionScope.STANDALONE_PHASE,
      )
    val standalone = IdeStatusPolicy.work(facts)
    assertEquals(IdeStatusLiveSection.PHASES, standalone.liveSection)
    assertEquals(IdeStatusDisplayGroup.PHASES, standalone.displayGroup)

    val goalLaunched = IdeStatusPolicy.work(facts.copy(launchScope = IdeStatusLaunchScope.GOAL))
    assertEquals(IdeStatusLiveSection.GOALS, goalLaunched.liveSection)
    assertEquals(IdeStatusDisplayGroup.GOALS, goalLaunched.displayGroup)
    assertFalse(goalLaunched.controlEligible)
  }

  @Test
  fun `an unfinished goal with no live runner needs you instead of leaving the board`() {
    val idle = IdeStatusPolicy.work(goalFacts().copy(lifecycle = IdeStatusLifecycleState.IDLE))
    assertEquals(IdeStatusLiveSection.NEEDS_YOU, idle.liveSection)
    assertTrue(idle.requiresAttention)
    assertFalse(idle.running)
    assertFalse(idle.controlEligible)

    val finished =
      IdeStatusPolicy.work(
        goalFacts().copy(lifecycle = IdeStatusLifecycleState.IDLE, finishedAt = Instant.EPOCH),
      )
    assertEquals(null, finished.liveSection)
    assertFalse(finished.requiresAttention)

    val idleTask =
      IdeStatusPolicy.work(
        goalFacts().copy(lifecycle = IdeStatusLifecycleState.IDLE, family = IdeStatusWorkflowFamily.FEATURE_VERIFY),
      )
    assertEquals(null, idleTask.liveSection)
    assertFalse(idleTask.requiresAttention)
  }

  private fun goalFacts(
    pauseRequested: Boolean = false,
    freshness: IdeStatusFreshness = IdeStatusFreshness.FRESH,
    controlTargetPresent: Boolean = true,
  ) = IdeStatusWorkPolicyFacts(
    lifecycle = IdeStatusLifecycleState.ACTIVE,
    family = IdeStatusWorkflowFamily.FEATURE_GOAL,
    scope = null,
    freshness = freshness,
    finishedAt = null,
    awaitingOperatorDecision = false,
    planningPrepared = false,
    controlTargetPresent = controlTargetPresent,
    pauseRequested = pauseRequested,
  )
}
