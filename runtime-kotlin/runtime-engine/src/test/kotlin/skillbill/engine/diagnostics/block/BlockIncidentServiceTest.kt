package skillbill.engine.diagnostics.block

import skillbill.ports.blockincident.InMemoryBlockIncidentRepository
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOwner
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitionDecision
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentTransitions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class BlockIncidentServiceTest {
  @Test
  fun `replayed phase observation links immutable incident and next attempt gets a new identity`() {
    val repository = InMemoryBlockIncidentRepository()
    val serviceClock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC)
    val first =
      BlockIncidentService.observeBlock(
        repository,
        serviceClock,
        observation(attempt = 1, reason = "first failure"),
      )
    val replay =
      BlockIncidentService.observeBlock(
        repository,
        serviceClock,
        observation(attempt = 1, reason = "changed text"),
      )
    val retry =
      BlockIncidentService.observeBlock(
        repository,
        serviceClock,
        observation(attempt = 2, reason = "first failure"),
      )

    assertSame(first, replay)
    assertEquals("first failure", replay.occurrence.reason)
    assertNotEquals(first.occurrence.occurrenceToken, retry.occurrence.occurrenceToken)
    assertEquals("first failure", retry.occurrence.reason)
  }

  @Test
  fun `expired claims reach crashed after two takeovers and pending pickup expires`() {
    val clock = MutableClock(Instant.parse("2026-10-21T12:00:00Z"))
    val repository = InMemoryBlockIncidentRepository(clock)
    val initial = BlockIncidentService.observeBlock(repository, clock, observation(attempt = 1, reason = "blocked"))
    val firstClaimAt = clock.instant()
    var claimed =
      assertIs<BlockIncidentTransitionDecision.Accepted>(
        BlockIncidentTransitions.claim(
          initial,
          firstClaimAt,
          initial.state.revision,
          BlockIncidentClaim("owner-0", 1, "host", 1, firstClaimAt.plusSeconds(90)),
        ),
      ).candidate
    clock.advance(Duration.ofSeconds(91))
    repeat(3) { takeover ->
      val now = clock.instant()
      val nextGeneration = (claimed.state.claim?.ownerGeneration ?: 0L) + 1L
      val ownerToken = "owner-${takeover + 1}"
      val decision =
        BlockIncidentTransitions.claim(
          claimed,
          now,
          claimed.state.revision,
          BlockIncidentClaim(ownerToken, nextGeneration, "host", 1, now.plusSeconds(90)),
        )
      claimed = assertIs<BlockIncidentTransitionDecision.Accepted>(decision).candidate
      if (takeover < 2) {
        clock.advance(Duration.ofSeconds(91))
      } else {
        val settled =
          BlockIncidentTransitions.settle(
            claimed,
            clock.instant(),
            BlockIncidentOwner(ownerToken, nextGeneration),
            BlockIncidentDiagnosis.CRASHED,
            null,
          )
        claimed = assertIs<BlockIncidentTransitionDecision.Accepted>(settled).candidate
      }
    }
    assertEquals(2, claimed.state.retryCount)
    assertEquals(BlockIncidentDiagnosis.CRASHED, claimed.state.diagnosis)

    val pending =
      BlockIncidentService.observeBlock(
        repository,
        clock,
        observation(attempt = 2, reason = "second block"),
      )
    clock.advance(Duration.ofHours(24).plusSeconds(1))
    val expired = BlockIncidentService.reconcilePending(repository, clock, 16)
    assertEquals(
      BlockIncidentDiagnosis.TIMED_OUT,
      expired.single { it.occurrence.occurrenceToken == pending.occurrence.occurrenceToken }.state.diagnosis,
    )
  }

  private fun observation(
    attempt: Int,
    reason: String,
  ) = BlockIncidentObservation(
    workflowId = "workflow-1",
    phaseId = "implement",
    attempt = attempt,
    reason = reason,
    failureDisposition = "needs_user_action",
    generation = null,
    exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
  )

  private class MutableClock(var time: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = time

    fun advance(duration: Duration) {
      time = time.plus(duration)
    }
  }
}
