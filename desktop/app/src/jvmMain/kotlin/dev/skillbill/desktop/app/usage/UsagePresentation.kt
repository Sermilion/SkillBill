package dev.skillbill.desktop.app.usage

import dev.skillbill.desktop.core.designsystem.usage.UsageUiMeter
import dev.skillbill.desktop.core.designsystem.usage.UsageUiState
import dev.skillbill.desktop.core.domain.usage.model.DecimalQuantity
import dev.skillbill.desktop.core.domain.usage.model.PartialUsageField
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import java.time.ZoneId
import kotlin.math.roundToLong
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

internal fun UsageProvider.uiName() = when (this) {
  UsageProvider.Codex -> "Codex"
  UsageProvider.Claude -> "Claude"
}

internal fun UsageProvider.agentId() = when (this) {
  UsageProvider.Codex -> "codex"
  UsageProvider.Claude -> "claude"
}

/** Agent name plus its allowance windows in the repository's ranked order. One short line replaces the meters when there are none. */
internal fun UsageSidebarState.toUsageUiState(now: Instant): UsageUiState {
  // An agent without a usage source shows its name and nothing else: no meters, no "no data" line.
  nameOnlyAgent?.let { return UsageUiState(provider = it.replaceFirstChar(Char::uppercase), agentId = it, nameOnly = true) }
  val meters = result?.meters().orEmpty().map { it.toUiMeter(now) }
  return UsageUiState(
    provider = provider?.uiName() ?: "Usage",
    agentId = provider?.agentId(),
    meters = meters,
    message = if (meters.isNotEmpty()) null else message(),
  )
}

/** One short line when there are no meters. Each says what is happening; no state reads as a bare "no data". */
private fun UsageSidebarState.message(): String = when (val result = result) {
  is UsageResult.SignInRequired -> "Not signed in"
  is UsageResult.Snapshot -> when {
    result.value.source.capability == UsageCapability.SessionStatusOnly && result.value.requestPermission == RequestPermission.Rejected -> "Limit reached"
    result.value.sessionWarning == true -> "Near limit"
    else -> "No limits reported"
  }
  is UsageResult.EmptySuccess -> "No limits reported"
  is UsageResult.Partial -> LOADING
  is UsageResult.Throttled -> "Refresh delayed"
  is UsageResult.TransientFailure -> COULD_NOT_READ
  is UsageResult.Unavailable -> result.reason.message()
  is UsageResult.Unsupported -> result.reason.message()
  null -> when {
    provider == null -> "No agent selected"
    unavailableReason != null -> COULD_NOT_READ
    else -> LOADING
  }
}

private fun UsageUnavailableReason.message(): String = when (code) {
  // Claude reports limits only alongside a reply; nothing can be read before the first one.
  UsageUnavailableCode.SessionObservationUnavailable -> "Shows after the next Claude reply"
  // Codex answers these while its first read is still on the way.
  UsageUnavailableCode.ProfileUnverified, UsageUnavailableCode.AwaitingCapabilityReader -> LOADING
  UsageUnavailableCode.Throttled -> "Refresh delayed"
  UsageUnavailableCode.UnsupportedPersonalSnapshot, UsageUnavailableCode.ProviderUnavailable, UsageUnavailableCode.TransientFailure -> COULD_NOT_READ
}

private const val LOADING = "Loading…"
private const val COULD_NOT_READ = "Couldn't read usage"

private fun UsageResult.meters(): List<UsageMeter> = when (this) {
  is UsageResult.Snapshot -> value.popupMeters
  is UsageResult.Partial -> fields.values.mapNotNull { (it as? PartialUsageField.Present)?.value }
  else -> emptyList()
}

internal fun UsageMeter.toUiMeter(now: Instant, zone: ZoneId = ZoneId.systemDefault()): UsageUiMeter {
  val expired = expired || resetAt?.let { it <= now } == true
  val (valueText, progress) = if (expired) "Awaiting update" to null else when (val value = value) {
    is UsageValue.PercentageUsed -> "${value.percent.percentText()}%" to (value.percent / 100.0).toFloat()
    is UsageValue.AmountUsed -> "${value.amount.display()} / ${value.limit.display()} ${value.limit.unit}" to value.barFraction?.toFloat()
    is UsageValue.Money -> "${value.amount.display()} ${value.currency}" to null
    is UsageValue.CreditBalance -> "${value.amount.display()} ${value.amount.unit}" to null
    UsageValue.Unlimited -> "Unlimited" to null
    is UsageValue.Status -> value.label to null
  }
  val resetIn = if (expired) "now" else resetAt?.let { deadline -> (deadline - now).inWholeSeconds.compactDuration() }
  // A model or pool window names its model, so "Weekly (Opus)" never reads as a second account-wide weekly limit.
  val scopedLabel = when (val meterScope = scope) {
    is UsageMeterScope.Model -> "$label (${meterScope.name})"
    is UsageMeterScope.Pool -> "$label (${meterScope.name})"
    UsageMeterScope.General -> label
  }
  return UsageUiMeter(
    id = bucketIdentity, label = scopedLabel, valueText = valueText, progress = progress,
    resetIn = resetIn, resetAt = resetAt?.takeIf { it > now }?.clockLabel(now, zone),
  )
}

/** "14:30" when the reset falls today, otherwise "Fri 14:30". */
private fun Instant.clockLabel(now: Instant, zone: ZoneId): String {
  val at = java.time.Instant.ofEpochSecond(epochSeconds).atZone(zone)
  val today = java.time.Instant.ofEpochSecond(now.epochSeconds).atZone(zone).toLocalDate()
  val pattern = if (at.toLocalDate() == today) "HH:mm" else "EEE HH:mm"
  return at.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** 81.0 reads as "81", 7.25 as "7.3": whole numbers drop the decimal, others keep one place. */
private fun Double.percentText(): String {
  val tenths = (this * 10).roundToLong()
  return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

private fun Long.compactDuration(): String = when {
  this < 3600 -> "${(this / 60).coerceAtLeast(1)}m"
  this < 86400 -> "${this / 3600}h ${this % 3600 / 60}m"
  else -> "${this / 86400}d ${this % 86400 / 3600}h"
}

private fun DecimalQuantity.display(): String {
  val digits = unscaledDigits
  if (scale == 0) return digits
  val negative = digits.startsWith("-")
  val raw = digits.removePrefix("-").padStart(scale + 1, '0')
  val split = raw.length - scale
  return (if (negative) "-" else "") + raw.substring(0, split) + "." + raw.substring(split)
}
