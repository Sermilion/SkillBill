package skillbill.telemetry

import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryProxyCapabilities
import skillbill.telemetry.model.TelemetrySettings
import java.time.LocalDate
import java.time.format.DateTimeParseException

fun parseRemoteStatsWindow(
  since: String = "",
  dateFrom: String = "",
  dateTo: String = "",
  today: LocalDate,
): Pair<String, String> {
  require(dateFrom.isBlank() || since.isBlank()) {
    "Use either since or date_from/date_to, not both."
  }
  val endDate = resolvedEndDate(dateTo, today)
  val startDate = resolvedStartDate(since, dateFrom, endDate)
  require(!startDate.isAfter(endDate)) {
    "date_from must be on or before date_to."
  }
  return startDate.toString() to endDate.toString()
}

fun validateRemoteStatsRequest(request: RemoteStatsRequest) {
  require(request.workflow in remoteStatsWorkflows) {
    "workflow must be one of: ${remoteStatsWorkflows.joinToString(", ")}."
  }
  require(request.groupBy.isBlank() || request.groupBy in listOf("day", "week")) {
    "group_by must be one of: day, week."
  }
}

fun validateIngestCapabilities(capabilities: TelemetryProxyCapabilities) {
  require(!capabilities.supportsIngest || capabilities.supportsEventDeduplication) {
    "Configured telemetry proxy accepts ingest but cannot carry the event deduplication property, " +
      "so a retried batch would be ingested as duplicate events. Capabilities URL: " +
      capabilities.capabilitiesUrl
  }
}

fun validateRemoteStatsCapabilities(
  request: RemoteStatsRequest,
  settings: TelemetrySettings,
  capabilities: TelemetryProxyCapabilities,
) {
  require(capabilities.supportsStats) {
    val capabilitiesUrl =
      capabilities.capabilitiesUrl.ifBlank {
        settings.proxyUrl.trimEnd('/') + "/capabilities"
      }
    "Configured telemetry proxy does not support remote stats yet. Capabilities URL: $capabilitiesUrl"
  }
  val supportedWorkflows = capabilities.supportedWorkflows
  require(supportedWorkflows.isEmpty() || request.workflow in supportedWorkflows) {
    "Configured telemetry proxy does not support workflow '${request.workflow}'. " +
      "Supported workflows: ${supportedWorkflows.joinToString(", ")}."
  }
}

private fun resolvedEndDate(
  dateTo: String,
  today: LocalDate,
): LocalDate =
  if (dateTo.isBlank()) {
    today
  } else {
    parseIsoDate(dateTo, "date_to")
  }

private fun resolvedStartDate(
  since: String,
  dateFrom: String,
  endDate: LocalDate,
): LocalDate =
  if (dateFrom.isNotBlank()) {
    parseIsoDate(dateFrom, "date_from")
  } else {
    endDate.minusDays((parseSinceDays(since.ifBlank { "30d" }) - 1).toLong())
  }

private fun parseIsoDate(
  rawValue: String,
  fieldName: String,
): LocalDate =
  try {
    LocalDate.parse(rawValue)
  } catch (error: DateTimeParseException) {
    throw IllegalArgumentException("$fieldName must use YYYY-MM-DD format.", error)
  }

private fun parseSinceDays(rawValue: String): Int {
  val normalized = rawValue.trim().lowercase()
  require(normalized.endsWith("d")) {
    "since must use <days>d format, for example 7d or 30d."
  }
  val days =
    normalized.dropLast(1).toIntOrNull()
      ?: throw IllegalArgumentException(
        "since must use <days>d format, for example 7d or 30d.",
      )
  require(days > 0) { "since must be greater than zero days." }
  return days
}
