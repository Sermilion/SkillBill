package skillbill.engine.work

import skillbill.engine.work.validation.validationChecksOf
import skillbill.ports.idestatus.model.ValidationCheckEvent
import skillbill.ports.idestatus.model.ValidationCheckEventRecord
import skillbill.ports.idestatus.model.ValidationCheckOutcome
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkValidationChecksTest {
  @Test
  fun `groups planned retries and skipped events in first event order`() {
    val checks =
      validationChecksOf(
        listOf(
          event(1, "planned", ValidationCheckEvent.PLANNED),
          event(2, "retry", ValidationCheckEvent.STARTED, attempt = 1).copy(note = "initial note"),
          event(3, "skip", ValidationCheckEvent.FINISHED, attempt = 1, outcome = ValidationCheckOutcome.SKIPPED)
            .copy(note = "not applicable"),
          event(4, "retry", ValidationCheckEvent.FINISHED, attempt = 1, outcome = ValidationCheckOutcome.FAILED),
          event(5, "retry", ValidationCheckEvent.STARTED, attempt = 2),
          event(6, "retry", ValidationCheckEvent.FINISHED, attempt = 2, outcome = ValidationCheckOutcome.PASSED),
        ),
      )
    assertEquals(listOf("planned", "retry", "skip"), checks.map { it.checkId })
    assertEquals(emptyList(), checks[0].attempts)
    assertEquals(listOf(1, 2), checks[1].attempts.map { it.number })
    assertEquals("initial note", checks[1].attempts.first().note)
    assertEquals(1, checks[2].attempts.size)
    assertEquals(null, checks[2].attempts.single().startedAt)
    assertEquals("not applicable", checks[2].attempts.single().note)
  }

  private fun event(
    sequence: Int,
    checkId: String,
    event: ValidationCheckEvent,
    attempt: Int = 0,
    outcome: ValidationCheckOutcome? = null,
  ) = ValidationCheckEventRecord(
    "invocation", sequence, checkId, event, checkId, "./gradlew check", ".", outcome, null, null, attempt,
    Instant.parse("2026-11-06T10:00:00Z").plusSeconds(sequence.toLong()),
  )
}
