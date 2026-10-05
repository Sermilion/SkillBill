package skillbill.telemetry

import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument

fun defaultLocalTelemetryConfig(installId: String): TelemetryConfigDocument =
  TelemetryConfigDocument(
    payload =
      TelemetryOpenDocument.from(
        mapOf(
          "install_id" to installId,
          "telemetry" to
            mapOf(
              "level" to "anonymous",
              "proxy_url" to "",
              "batch_size" to DEFAULT_TELEMETRY_BATCH_SIZE,
            ),
        ),
      ),
  )

fun TelemetryConfigDocument.withTelemetryLevel(
  level: String,
  configPath: String,
): TelemetryConfigDocument {
  val updatedPayload = payload.toMutableMap()
  val telemetry =
    when (val telemetryRaw = updatedPayload["telemetry"]) {
      null -> mutableMapOf<String, Any?>()
      is Map<*, *> ->
        telemetryRaw.entries
          .filter { it.key is String }
          .associate { it.key as String to it.value }
          .toMutableMap()
      else -> throw IllegalArgumentException(
        "Telemetry config at '$configPath' must contain a 'telemetry' object.",
      )
    }
  telemetry["level"] = level
  telemetry.remove("enabled")
  updatedPayload["telemetry"] = telemetry
  return TelemetryConfigDocument(TelemetryOpenDocument.from(updatedPayload))
}

fun parseTelemetryBoolValue(
  rawValue: String,
  name: String,
): Boolean = parseTelemetryBoolValueOrNull(rawValue) ?: throw IllegalArgumentException(telemetryBoolValueError(name))

fun parseTelemetryBoolValueOrNull(rawValue: String): Boolean? =
  when (rawValue.trim().lowercase()) {
    "1", "true", "yes", "on" -> true
    "0", "false", "no", "off" -> false
    else -> null
  }

fun telemetryBoolValueError(name: String): String = "$name must be one of: 1, 0, true, false, yes, no, on, off."

fun parsePositiveTelemetryInt(
  rawValue: String,
  name: String,
): Int =
  parsePositiveTelemetryIntOrNull(rawValue) ?: throw IllegalArgumentException(positiveTelemetryIntError(rawValue, name))

fun parsePositiveTelemetryIntOrNull(rawValue: String): Int? = rawValue.toIntOrNull()?.takeIf { it > 0 }

fun positiveTelemetryIntError(
  rawValue: String,
  name: String,
): String = if (rawValue.toIntOrNull() == null) "$name must be an integer." else "$name must be greater than zero."

fun parseTelemetryLevelValue(
  rawValue: String,
  name: String,
): String = parseTelemetryLevelValueOrNull(rawValue) ?: throw IllegalArgumentException(telemetryLevelValueError(name))

fun parseTelemetryLevelValueOrNull(rawValue: String): String? {
  val normalized = rawValue.trim().lowercase()
  return normalized.takeIf { it in telemetryLevels }
}

fun telemetryLevelValueError(name: String): String = "$name must be one of: ${telemetryLevels.joinToString(", ")}."
