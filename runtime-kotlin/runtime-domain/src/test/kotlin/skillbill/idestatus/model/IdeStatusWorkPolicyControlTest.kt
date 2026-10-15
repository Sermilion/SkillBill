package skillbill.idestatus.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeStatusWorkPolicyControlTest {
  @Test
  fun `a pending pause keeps stop available but refuses a second pause`() {
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
