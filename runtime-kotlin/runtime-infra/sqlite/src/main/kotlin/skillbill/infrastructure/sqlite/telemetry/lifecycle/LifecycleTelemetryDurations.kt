package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.infrastructure.sqlite.core.ops.recordDegradedValue
import skillbill.infrastructure.sqlite.telemetry.goal.GoalTelemetryPayloadKeys
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal fun durationSeconds(
  row: Map<String, Any?>,
  diagnostics: RuntimeDiagnostics,
): Int {
  val startedAt = row.stringOrEmpty(GoalTelemetryPayloadKeys.STARTED_AT)
  val finishedAt = row.stringOrEmpty(GoalTelemetryPayloadKeys.FINISHED_AT)
  return if (startedAt.isBlank() || finishedAt.isBlank()) {
    0
  } else {
    parseDurationSeconds(startedAt, finishedAt, diagnostics)
  }
}

internal fun parseDurationSeconds(
  startedAt: String,
  finishedAt: String,
  diagnostics: RuntimeDiagnostics,
): Int =
  runCatching {
    val start = LocalDateTime.parse(startedAt.replace(' ', 'T'))
    val end = LocalDateTime.parse(finishedAt.replace(' ', 'T'))
    maxOf(0, ChronoUnit.SECONDS.between(start, end).toInt())
  }.getOrElse { error ->
    if (error is DateTimeParseException) {
      diagnostics.recordDegradedValue(
        seam = "telemetry.duration_seconds",
        expected = "parseable started_at and finished_at timestamps",
        used = "started_at=$startedAt; finished_at=$finishedAt",
        error = error,
      )
    }
    0
  }
