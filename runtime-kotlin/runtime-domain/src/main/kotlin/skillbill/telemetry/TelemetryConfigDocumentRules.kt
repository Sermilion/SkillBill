package skillbill.telemetry

import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryConfigDocumentParse
import skillbill.telemetry.model.TelemetryConfigDocumentSettings

fun parseTelemetryConfigDocument(
  configPath: String,
  config: TelemetryConfigDocument,
): TelemetryConfigDocumentParse {
  val payload = config.payload
  val telemetryRaw = payload[TelemetryConfigFileKeys.TELEMETRY]
  val telemetry =
    when (telemetryRaw) {
      null -> emptyMap()
      is Map<*, *> -> telemetryRaw.entries.filter { it.key is String }.associate { it.key as String to it.value }
      else -> return TelemetryConfigDocumentParse.Invalid(
        TelemetryConfigFileKeys.TELEMETRY,
        "Telemetry config at '$configPath' must contain a 'telemetry' object.",
      )
    }
  val level =
    when (val parsed = parseConfigLevel(telemetry)) {
      is ConfigScalar.Valid -> parsed.value
      is ConfigScalar.Invalid -> return TelemetryConfigDocumentParse.Invalid(parsed.keyPath, parsed.reason)
    }
  val batchSize =
    when (val parsed = parseConfigBatchSize(telemetry)) {
      is ConfigScalar.Valid -> parsed.value
      is ConfigScalar.Invalid -> return TelemetryConfigDocumentParse.Invalid(parsed.keyPath, parsed.reason)
    }
  return TelemetryConfigDocumentParse.Valid(
    TelemetryConfigDocumentSettings(
      level = level,
      customProxyUrl = telemetry[TelemetryConfigFileKeys.PROXY_URL]?.toString()?.trim().orEmpty(),
      batchSize = batchSize,
      installId = payload[TelemetryConfigFileKeys.INSTALL_ID]?.toString()?.trim().orEmpty(),
    ),
  )
}

private sealed interface ConfigScalar<out T> {
  data class Valid<T>(val value: T) : ConfigScalar<T>

  data class Invalid(val keyPath: String, val reason: String) : ConfigScalar<Nothing>
}

private fun parseConfigLevel(telemetry: Map<String, Any?>): ConfigScalar<String> {
  val level = telemetry[TelemetryConfigFileKeys.LEVEL]
  val enabled = telemetry[TelemetryConfigFileKeys.ENABLED]
  if (level != null) {
    val value = parseTelemetryLevelValueOrNull(level.toString())
    return if (value == null) {
      ConfigScalar.Invalid(
        "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.LEVEL}",
        telemetryLevelValueError("telemetry.level"),
      )
    } else {
      ConfigScalar.Valid(value)
    }
  }
  val value =
    when (enabled) {
      is Boolean -> enabled
      is String -> parseTelemetryBoolValueOrNull(enabled)
      null -> true
      else -> enabled == true
    }
  return if (value == null) {
    ConfigScalar.Invalid(
      "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.ENABLED}",
      telemetryBoolValueError("telemetry.enabled"),
    )
  } else {
    ConfigScalar.Valid(if (value) "anonymous" else "off")
  }
}

private fun parseConfigBatchSize(telemetry: Map<String, Any?>): ConfigScalar<Int> {
  val raw = telemetry[TelemetryConfigFileKeys.BATCH_SIZE]
  val value =
    when (raw) {
      is Int -> raw
      is Number -> raw.toInt()
      null -> DEFAULT_TELEMETRY_BATCH_SIZE
      else -> parsePositiveTelemetryIntOrNull(raw.toString())
    }
  return if (value == null) {
    ConfigScalar.Invalid(
      "${TelemetryConfigFileKeys.TELEMETRY}.${TelemetryConfigFileKeys.BATCH_SIZE}",
      positiveTelemetryIntError(raw.toString(), "telemetry.batch_size"),
    )
  } else {
    ConfigScalar.Valid(value)
  }
}
