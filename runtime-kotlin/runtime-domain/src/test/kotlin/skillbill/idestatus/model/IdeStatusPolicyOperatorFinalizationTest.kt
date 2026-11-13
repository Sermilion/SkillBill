package skillbill.idestatus.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeStatusPolicyOperatorFinalizationTest {
  private val observedAt = Instant.parse("2026-10-13T12:00:00Z")

  @Test
  fun `unfinalized terminal standalone stays in needs you`() {
    val policy =
      IdeStatusPolicy.work(
        IdeStatusWorkPolicyFacts(
          lifecycle = IdeStatusLifecycleState.TERMINAL,
          family = null,
          scope = IdeStatusExecutionScope.STANDALONE_PHASE,
          freshness = IdeStatusFreshness.STALE,
          finishedAt = observedAt.minusSeconds(60),
          operatorFinalizedAt = null,
          awaitingOperatorDecision = false,
          planningPrepared = false,
          controlTargetPresent = true,
          observedAt = observedAt,
        ),
      )

    assertEquals(IdeStatusLiveSection.NEEDS_YOU, policy.liveSection)
    assertTrue(policy.requiresAttention)
    assertFalse(policy.recentlyFinishedPhase)
  }

  @Test
  fun `goal finished today still follows execution completion not operator finalization`() {
    val finishedAt = observedAt.minusSeconds(120)
    val policy =
      IdeStatusPolicy.work(
        IdeStatusWorkPolicyFacts(
          lifecycle = IdeStatusLifecycleState.TERMINAL,
          family = IdeStatusWorkflowFamily.FEATURE_GOAL,
          scope = null,
          freshness = IdeStatusFreshness.STALE,
          finishedAt = finishedAt,
          operatorFinalizedAt = null,
          awaitingOperatorDecision = false,
          planningPrepared = false,
          controlTargetPresent = true,
          observedAt = observedAt,
          successfulGoalCompletion = true,
        ),
      )

    assertEquals(null, policy.liveSection)
    assertTrue(policy.doneWithinDay)
    assertFalse(policy.requiresAttention)
  }
}
