package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.ValidationCheck
import dev.skillbill.desktop.core.domain.model.ValidationCheckAttempt
import dev.skillbill.desktop.core.domain.model.ValidationCheckResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ValidationChecksViewTest {
  @Test
  fun `settled unfinished attempts leave done and fixed retries count only passed checks`() {
    val started = Instant.fromEpochMilliseconds(1_000_000)
    val checks = listOf(
      check("passed", attempt(1, started, at(2), ValidationCheckResult.Passed)),
      check("retried", attempt(1, started, at(1), ValidationCheckResult.Failed), attempt(2, started, at(3), ValidationCheckResult.Passed)),
      check("running", attempt(1, started, null, null)),
      check("pending"),
    )
    val active = validationChecksSummary(execution(checks), live = true)
    assertEquals(listOf(ValidationCheckState.Passed, ValidationCheckState.Passed, ValidationCheckState.Running, ValidationCheckState.Pending), active.rows.map { it.state })
    assertEquals(2, active.done)
    assertEquals(1, active.retried)
    assertEquals(1, active.fixedFailedAttempts)
    val settled = validationChecksSummary(execution(checks), live = false)
    assertEquals(2, settled.done)
    assertEquals(ValidationCheckState.NotFinished, settled.rows[2].state)
  }

  private fun check(id: String, vararg attempts: ValidationCheckAttempt) = ValidationCheck(id, id, "check $id", ".", attempts.toList())

  private fun attempt(number: Int, started: Instant?, finished: Instant?, result: ValidationCheckResult?) =
    ValidationCheckAttempt(number, started, finished, result, null, null)

  private fun at(seconds: Long) = Instant.fromEpochMilliseconds(1_000_000 + seconds * 1_000)

  private fun execution(checks: List<ValidationCheck>) = ExecutionInstance(
    invocationId = "invocation", executionId = "execution", lifecycleState = "active", startedAt = Instant.fromEpochMilliseconds(0),
    updatedAt = Instant.fromEpochMilliseconds(0), finishedAt = null, result = null, restoration = null, qaVerdict = null,
    qaReportText = null, planReport = null, liveChecks = true, validationChecks = checks,
  )
}
