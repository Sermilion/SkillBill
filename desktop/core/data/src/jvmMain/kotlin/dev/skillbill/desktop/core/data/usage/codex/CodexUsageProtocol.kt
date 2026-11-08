package dev.skillbill.desktop.core.data.usage.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

internal data class CodexQuotaWindow(
  val durationMinutes: Long,
  val usedPercent: Double,
  val resetsAtUnixSeconds: Long?,
)

internal data class CodexQuotaBucket(
  val limitId: String,
  val limitName: String? = null,
  val primary: CodexQuotaWindow?,
  val secondary: CodexQuotaWindow?,
)

internal sealed interface CodexQuotaParse {
  data class Valid(val buckets: List<CodexQuotaBucket>) : CodexQuotaParse
  data object Invalid : CodexQuotaParse
}

internal object CodexUsageProtocol {
  fun parseQuota(element: JsonElement): CodexQuotaParse {
    val root = element as? JsonObject ?: return CodexQuotaParse.Invalid
    val preferred = root["rateLimitsByLimitId"]
    val selected = preferred ?: root["rateLimits"] ?: return CodexQuotaParse.Invalid
    val entries = when {
      preferred == null && selected is JsonObject && ("primary" in selected || "secondary" in selected) -> {
        val id = (selected["limitId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
          ?: return CodexQuotaParse.Invalid
        listOf(id to selected)
      }
      selected is JsonObject -> selected.entries.map { it.key to it.value }
      selected is JsonArray -> selected.map { value ->
        val id = (value as? JsonObject)?.get("limitId")?.let { it as? JsonPrimitive }?.content
          ?: return CodexQuotaParse.Invalid
        id to value
      }
      else -> return CodexQuotaParse.Invalid
    }
    val buckets = entries.map { (id, value) ->
      val bucket = value as? JsonObject ?: return CodexQuotaParse.Invalid
      val primaryElement = bucket["primary"]
      val primary = if (primaryElement == null || primaryElement == JsonNull) null
      else parseWindow(primaryElement) ?: return CodexQuotaParse.Invalid
      val secondaryElement = bucket["secondary"]
      val secondary = if (secondaryElement == null || secondaryElement == JsonNull) null
      else parseWindow(secondaryElement) ?: return CodexQuotaParse.Invalid
      if (id.isBlank()) return CodexQuotaParse.Invalid
      val name = (bucket["limitName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
      CodexQuotaBucket(id, name, primary, secondary)
    }
    return CodexQuotaParse.Valid(buckets)
  }

  private fun parseWindow(element: JsonElement?): CodexQuotaWindow? {
    val window = element as? JsonObject ?: return null
    val duration = window["windowDurationMins"]?.primitiveLong() ?: return null
    val percentage = window["usedPercent"]?.primitiveDouble() ?: return null
    val resetElement = window["resetsAt"]
    val reset = if (resetElement == null || resetElement == JsonNull) null
    else resetElement.primitiveLong() ?: return null
    if (duration <= 0 || duration > Long.MAX_VALUE / 60 || percentage !in 0.0..100.0 || (reset != null && !representsInstant(reset))) return null
    return CodexQuotaWindow(duration, percentage, reset)
  }

  private fun representsInstant(seconds: Long): Boolean = try {
    Instant.fromEpochSeconds(seconds)
    true
  } catch (_: IllegalArgumentException) {
    false
  }

  private fun JsonElement.primitiveLong(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
  private fun JsonElement.primitiveDouble(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf(Double::isFinite)

  fun parseLine(line: String): JsonObject? = try {
    Json.parseToJsonElement(line).jsonObject
  } catch (_: Exception) {
    null
  }
}
