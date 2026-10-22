package skillbill.engine.diagnostics.block

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import skillbill.ports.blockincident.InMemoryBlockIncidentRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BlockIncidentServiceTest {
  @Test
  fun `replayed phase observation links immutable incident and next attempt gets a new identity`() {
    val repository = InMemoryBlockIncidentRepository()
    val serviceClock = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC)
    val first = BlockIncidentService.observeBlock(repository, serviceClock, observation(attempt = 1, reason = "first failure"))
    val replay = BlockIncidentService.observeBlock(repository, serviceClock, observation(attempt = 1, reason = "changed text"))
    val retry = BlockIncidentService.observeBlock(repository, serviceClock, observation(attempt = 2, reason = "first failure"))

    assertSame(first, replay)
    assertEquals("first failure", replay.occurrence.reason)
    assertNotEquals(first.occurrence.occurrenceToken, retry.occurrence.occurrenceToken)
    assertEquals("first failure", retry.occurrence.reason)
  }

  private fun observation(attempt: Int, reason: String) =
    BlockIncidentObservation(
      workflowId = "workflow-1",
      phaseId = "implement",
      attempt = attempt,
      reason = reason,
      failureDisposition = "needs_user_action",
      generation = null,
      exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
    )
}
