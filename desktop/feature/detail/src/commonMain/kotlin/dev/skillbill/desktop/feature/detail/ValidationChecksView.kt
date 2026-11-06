package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.ValidationCheck
import dev.skillbill.desktop.core.domain.model.ValidationCheckResult
import dev.skillbill.desktop.core.domain.model.ValidationCheckAttempt

internal enum class ValidationCheckState(val word: String) {
  Passed("passed"), Running("running"), Blocked("blocked"), Failed("failed"), Skipped("skipped"), Pending("pending"), NotFinished("not finished"),
}

internal fun ValidationCheck.state(live: Boolean): ValidationCheckState {
  val last = attempts.lastOrNull() ?: return ValidationCheckState.Pending
  last.result?.let { return it.displayState() }
  return if (last.startedAt != null && live) ValidationCheckState.Running else if (last.startedAt != null) ValidationCheckState.NotFinished else ValidationCheckState.Pending
}

internal data class ValidationCheckRow(
  val check: ValidationCheck,
  val state: ValidationCheckState,
  val lastNote: String?,
  val expandable: Boolean,
  val inlineReason: String?,
)

internal data class ValidationChecksSummary(
  val rows: List<ValidationCheckRow>,
  val tally: List<Pair<ValidationCheckState, Int>>,
  val done: Int,
  val total: Int,
  val retried: Int,
  val fixedFailedAttempts: Int,
  val running: ValidationCheckRow?,
)

internal fun validationChecksSummary(run: ExecutionInstance, live: Boolean): ValidationChecksSummary {
  val rows = run.validationChecks.map { check ->
    val state = check.state(live)
    val note = check.attempts.asReversed().firstNotNullOfOrNull { it.note }
    ValidationCheckRow(check, state, note, check.attempts.size > 1 || (note != null && state != ValidationCheckState.Blocked && state != ValidationCheckState.Skipped), note.takeIf { state == ValidationCheckState.Blocked || state == ValidationCheckState.Skipped })
  }
  val order = listOf(ValidationCheckState.Passed, ValidationCheckState.Running, ValidationCheckState.Blocked, ValidationCheckState.Failed, ValidationCheckState.Skipped, ValidationCheckState.Pending, ValidationCheckState.NotFinished)
  val tally = order.mapNotNull { state -> state to rows.count { it.state == state } }.filter { it.second > 0 }
  val running = rows.filter { it.state == ValidationCheckState.Running }.maxByOrNull { it.check.attempts.last().startedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
  return ValidationChecksSummary(
    rows, tally, rows.count { it.state !in setOf(ValidationCheckState.Pending, ValidationCheckState.Running, ValidationCheckState.NotFinished) },
    rows.size, rows.count { it.check.attempts.size > 1 },
    rows.sumOf { row -> if (row.state == ValidationCheckState.Passed) row.check.attempts.dropLast(1).count { it.result == ValidationCheckResult.Failed } else 0 }, running,
  )
}

internal fun validationCheckIsOpen(
  row: ValidationCheckRow,
  invocationId: String,
  live: Boolean,
  overrides: Map<ValidationCheckKey, Boolean>,
): Boolean = row.expandable && (overrides[ValidationCheckKey(invocationId, row.check.checkId)] ?: (live && row.check.attempts.size > 1))

internal fun ValidationCheckResult.displayState() = when (this) {
  ValidationCheckResult.Passed -> ValidationCheckState.Passed
  ValidationCheckResult.Failed -> ValidationCheckState.Failed
  ValidationCheckResult.Blocked -> ValidationCheckState.Blocked
  ValidationCheckResult.Skipped -> ValidationCheckState.Skipped
}

internal fun ValidationCheckAttempt.durationMs(): Long? {
  val start = startedAt ?: return null
  val finish = finishedAt ?: return null
  return (finish - start).inWholeMilliseconds.coerceAtLeast(0)
}

internal fun ValidationCheck.totalDurationMs(): Long = attempts.sumOf { it.durationMs() ?: 0L }
