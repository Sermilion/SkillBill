package skillbill.infrastructure.sqlite.review.stats

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.sqlite.core.ops.degradedValuePreview
import skillbill.infrastructure.sqlite.core.ops.recordDegradedValue
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.sql.ResultSet
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Locale

internal fun rate(
  count: Int,
  total: Int,
): Double =
  if (total == 0) {
    0.0
  } else {
    String.format(Locale.US, "%.3f", count.toDouble() / total).toDouble()
  }

internal fun average(values: List<Int>): Double =
  if (values.isEmpty()) {
    0.0
  } else {
    String.format(Locale.US, "%.2f", values.average()).toDouble()
  }

internal fun parseJsonList(
  rawValue: Any?,
  diagnostics: RuntimeDiagnostics,
): List<Any?> =
  when (rawValue) {
    null -> emptyList()
    is String -> {
      val trimmed = rawValue.trim()
      if (trimmed.isEmpty()) {
        emptyList()
      } else {
        try {
          JsonCodec.parseJsonArrayStrict(trimmed)
        } catch (error: SkillBillRuntimeException) {
          error.rethrowUnless(error.isShellContentContractFailure())
          diagnostics.recordDegradedValue(
            seam = "review_stats.json_array",
            expected = "strict JSON array",
            used = trimmed.degradedValuePreview(),
            error = error,
          )
          emptyList()
        }
      }
    }
    else -> {
      diagnostics.recordDegradedValue(
        seam = "review_stats.json_array",
        expected = "string JSON array",
        used = rawValue::class.simpleName.orEmpty(),
      )
      emptyList()
    }
  }

internal fun durationSeconds(
  row: Map<String, Any?>,
  diagnostics: RuntimeDiagnostics,
): Int {
  val startedAt = row.stringValue("started_at")
  val finishedAt = row.stringValue("finished_at")
  if (startedAt.isEmpty() || finishedAt.isEmpty()) {
    return 0
  }
  return runCatching {
    val start = LocalDateTime.parse(startedAt.replace(" ", "T"))
    val end = LocalDateTime.parse(finishedAt.replace(" ", "T"))
    Duration.between(start, end).seconds.coerceAtLeast(0).toInt()
  }.getOrElse { error ->
    if (error is DateTimeParseException) {
      diagnostics.recordDegradedValue(
        seam = "review_stats.duration_seconds",
        expected = "parseable started_at and finished_at timestamps",
        used = "started_at=$startedAt; finished_at=$finishedAt",
        error = error,
      )
    }
    0
  }
}

internal fun collectRows(resultSet: ResultSet): List<Map<String, Any?>> {
  val metadata = resultSet.metaData
  val columnNames = (1..metadata.columnCount).map(metadata::getColumnLabel)
  return buildList {
    while (resultSet.next()) {
      add(columnNames.associateWith(resultSet::getObject))
    }
  }
}

internal fun Map<String, Any?>.booleanValue(key: String): Boolean =
  when (val value = this[key]) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value == "1" || value.equals("true", ignoreCase = true)
    else -> false
  }

internal fun Map<String, Any?>.intValue(key: String): Int =
  when (val value = this[key]) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
  }

internal fun Map<String, Any?>.stringValue(key: String): String = this[key]?.toString().orEmpty()

internal fun Map<String, Any?>.nullableIntValue(key: String): Int? =
  when (val value = this[key]) {
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
  }
