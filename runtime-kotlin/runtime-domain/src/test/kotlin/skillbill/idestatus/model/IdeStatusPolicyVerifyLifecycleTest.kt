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
    val policy = IdeStatusPolicy.work(facts(lifecycle))
    assertEquals(IdeStatusLiveSection.OPERATIONS, policy.liveSection)
    assertEquals(IdeStatusDisplayGroup.OPERATIONS, policy.displayGroup)
    assertEquals(true, policy.running)
    val entry = IdeStatusBoardEntry("verify", "repo", lifecycle, policy)
    val board = IdeStatusPolicy.arrange(listOf(entry))
    assertEquals(listOf(entry), board.liveOperations)
    assertEquals(emptyList(), board.livePhases)
    assertEquals(1, board.running)
  }

  @Test
  fun `finished verification stays in recent operations without becoming a finished phase`() {
    val policy =
      IdeStatusPolicy.work(
        facts(IdeStatusLifecycleState.TERMINAL).copy(
          finishedAt = observedAt.minusSeconds(30),
          observedAt = observedAt,
        ),
      )
    val entry = IdeStatusBoardEntry("verify", "repo", IdeStatusLifecycleState.TERMINAL, policy)
    val board = IdeStatusPolicy.arrange(listOf(entry))

    assertEquals(listOf(entry), board.finishedOperations)
    assertEquals(emptyList(), board.finishedPhases)
    assertEquals(0, board.running)
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
