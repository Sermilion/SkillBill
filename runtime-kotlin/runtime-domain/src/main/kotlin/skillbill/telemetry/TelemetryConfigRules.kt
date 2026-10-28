package skillbill.telemetry

import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryOpenDocument
import java.net.URI
import java.net.URISyntaxException

fun defaultLocalTelemetryConfig(installId: String): TelemetryConfigDocument =
  TelemetryConfigDocument(
    payload =
      TelemetryOpenDocument.from(
        mapOf(
          TelemetryConfigFileKeys.INSTALL_ID to installId,
          TelemetryConfigFileKeys.TELEMETRY to
            mapOf(
              TelemetryConfigFileKeys.LEVEL to "anonymous",
              TelemetryConfigFileKeys.PROXY_URL to "",
              TelemetryConfigFileKeys.BATCH_SIZE to DEFAULT_TELEMETRY_BATCH_SIZE,
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
    when (val telemetryRaw = updatedPayload[TelemetryConfigFileKeys.TELEMETRY]) {
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
  telemetry[TelemetryConfigFileKeys.LEVEL] = level
  telemetry.remove(TelemetryConfigFileKeys.ENABLED)
  updatedPayload[TelemetryConfigFileKeys.TELEMETRY] = telemetry
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

fun telemetryProxyUrlError(value: String): String? =
  try {
    val uri = URI(value.trim())
    if (uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https") && uri.host != null) {
      null
    } else {
      "must be an absolute http or https URL with a host."
    }
  } catch (_: URISyntaxException) {
    "must be an absolute http or https URL with a host."
  }

fun parseTelemetryLevelValue(
  rawValue: String,
  name: String,
): String = parseTelemetryLevelValueOrNull(rawValue) ?: throw IllegalArgumentException(telemetryLevelValueError(name))

fun parseTelemetryLevelValueOrNull(rawValue: String): String? {
  val normalized = rawValue.trim().lowercase()
  return normalized.takeIf { it in telemetryLevels }
}

fun telemetryLevelValueError(name: String): String = "$name must be one of: ${telemetryLevels.joinToString(", ")}."
