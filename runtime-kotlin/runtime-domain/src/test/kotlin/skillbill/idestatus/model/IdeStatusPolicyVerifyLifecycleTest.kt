package skillbill.idestatus.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdeStatusPolicyVerifyLifecycleTest {
  private val observedAt = Instant.parse("2026-10-13T12:00:00Z")

  @Test
  fun `a running verify run that stopped reporting is no longer active`() {
    val lifecycle =
      IdeStatusPolicy.verifyLifecycle(
        IdeStatusLifecycleState.ACTIVE,
        updatedAt = observedAt.minus(IdeStatusPolicy.VERIFY_ABANDONED_AFTER).minusSeconds(1),
        observedAt = observedAt,
      )

    assertEquals(IdeStatusLifecycleState.IDLE, lifecycle)
    assertNull(IdeStatusPolicy.work(facts(lifecycle)).liveSection)
  }

  @Test
  fun `a verify run inside the abandonment window stays active`() {
    val lifecycle =
      IdeStatusPolicy.verifyLifecycle(
        IdeStatusLifecycleState.ACTIVE,
        updatedAt = observedAt.minus(IdeStatusPolicy.VERIFY_ABANDONED_AFTER),
        observedAt = observedAt,
      )

    assertEquals(IdeStatusLifecycleState.ACTIVE, lifecycle)
    assertEquals(IdeStatusLiveSection.PHASES, IdeStatusPolicy.work(facts(lifecycle)).liveSection)
  }

  @Test
  fun `non-running verify lifecycles are never rewritten`() {
    val longAgo = observedAt.minus(IdeStatusPolicy.VERIFY_ABANDONED_AFTER.multipliedBy(10))

    listOf(IdeStatusLifecycleState.BLOCKED, IdeStatusLifecycleState.PAUSED, IdeStatusLifecycleState.TERMINAL)
      .forEach { durable ->
        assertEquals(durable, IdeStatusPolicy.verifyLifecycle(durable, longAgo, observedAt))
      }
  }

  private fun facts(lifecycle: IdeStatusLifecycleState) =
    IdeStatusWorkPolicyFacts(
      lifecycle = lifecycle,
      family = IdeStatusWorkflowFamily.FEATURE_VERIFY,
      scope = null,
      freshness = IdeStatusFreshness.STALE,
      finishedAt = null,
      awaitingOperatorDecision = false,
      planningPrepared = false,
      controlTargetPresent = true,
    )
}
