package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Meters from a Claude `rate_limit_event`'s `rate_limit_info`. Each `unifiedWindows` entry, such as `five_hour` or
 * `seven_day`, carries a 0..1 `utilization` and an epoch-second `resetsAt`. Older CLIs report only the top-level window
 * named by `rateLimitType`. A window without a utilization yields no meter.
 */
internal fun claudeRateLimitMeters(info: JsonObject, observedAt: Instant): List<UsageMeter> {
  val windows = info.obj("unifiedWindows")?.mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }
    ?: info.string("rateLimitType")?.let { listOf(it to info) }
    ?: return emptyList()
  return windows.mapNotNull { (key, window) -> window.toMeter(key, observedAt) }
}

private fun JsonObject.toMeter(key: String, observedAt: Instant): UsageMeter? {
  val utilization = (get("utilization") as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: return null
  val percent = UsageValue.PercentageUsed.create((utilization * 1000).roundToLong().coerceIn(0, 1000) / 10.0) ?: return null
  val window = CLAUDE_WINDOWS.entries.firstOrNull { key == it.key || key.startsWith(it.key + "_") }
  val model = window?.let { key.removePrefix(it.key).removePrefix("_") }?.takeIf(String::isNotEmpty)
  return UsageMeter.create(
    bucketIdentity = "claude:$key",
    label = window?.value?.label ?: key.replace('_', ' ').replaceFirstChar(Char::uppercase),
    scope = model?.let { UsageMeterScope.Model(it.replaceFirstChar(Char::uppercase)) } ?: UsageMeterScope.General,
    observedAt = observedAt,
    resetAt = (get("resetsAt") as? JsonPrimitive)?.longOrNull?.let(Instant::fromEpochSeconds),
    period = window?.value?.seconds?.let(UsagePeriod.Rolling::create) ?: UsagePeriod.Unknown,
    value = percent,
  )
}

private class ClaudeWindow(val label: String, val seconds: Long)

private val CLAUDE_WINDOWS = mapOf(
  "five_hour" to ClaudeWindow("5 hour", 5 * 3600),
  "seven_day" to ClaudeWindow("Weekly", 7 * 24 * 3600),
)
