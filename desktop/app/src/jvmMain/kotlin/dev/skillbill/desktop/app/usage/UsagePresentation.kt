package dev.skillbill.desktop.app.usage

import dev.skillbill.desktop.core.designsystem.usage.UsageUiMeter
import dev.skillbill.desktop.core.designsystem.usage.UsageUiState
import dev.skillbill.desktop.core.domain.usage.model.DecimalQuantity
import dev.skillbill.desktop.core.domain.usage.model.PartialUsageField
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageFreshness
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageWorkspaceScope
import kotlin.time.Instant

internal fun UsageProvider.uiName() = when (this) {
  UsageProvider.Codex -> "Codex"
  UsageProvider.Claude -> "Claude"
  UsageProvider.Cursor -> "Cursor"
}

internal fun UsageSidebarState.toUsageUiState(now: Instant, availability: UsageRefreshAvailability?): UsageUiState {
  val name = provider?.uiName() ?: "Usage"
  val mapped = result?.toUiData(provider, now)
  return UsageUiState(
    provider = name,
    account = if (mapped?.sessionOnly == true) "" else listOfNotNull(accountLabel, mapped?.scope).filter(String::isNotBlank).joinToString(" · "),
    accountExplanation = accountExplanation.takeUnless { mapped?.sessionOnly == true },
    contextLabel = contextLabel.takeIf { contextual },
    source = mapped?.source ?: provider?.uiName(),
    observationAge = mapped?.observedAt,
    status = when {
      availability == UsageRefreshAvailability.Checking -> "Checking for updates"
      mapped != null -> mapped.status
      unavailableReason != null -> "Unavailable"
      demandEligible -> "Loading usage data"
      else -> "Usage is inactive"
    },
    reason = unavailableReason ?: mapped?.reason ?: (availability as? UsageRefreshAvailability.Disabled)?.reason?.refreshActionExplanation(provider, result is UsageResult.SignInRequired),
    requestPermission = mapped?.permission,
    cardMeters = mapped?.cardMeters.orEmpty(),
    detailMeters = mapped?.detailMeters.orEmpty(),
    checking = mapped?.checking == true || availability == UsageRefreshAvailability.Checking,
    stale = mapped?.stale == true,
    expired = mapped?.expired == true,
    sessionOnly = mapped?.sessionOnly == true,
  )
}

private data class UiResult(
  val source: String? = null,
  val scope: String? = null,
  val observedAt: String? = null,
  val status: String,
  val reason: String? = null,
  val permission: String? = null,
  val cardMeters: List<UsageUiMeter> = emptyList(),
  val detailMeters: List<UsageUiMeter> = emptyList(),
  val checking: Boolean = false,
  val stale: Boolean = false,
  val expired: Boolean = false,
  val sessionOnly: Boolean = false,
)

private fun UsageResult.toUiData(selectedProvider: UsageProvider?, now: Instant): UiResult = when (this) {
  is UsageResult.Snapshot -> {
    val value = value
    val sourceObservationTime = value.sourceObservationTime
    UiResult(
      source = value.source.provider.uiName(),
      scope = value.subject.scopeLabel(),
      observedAt = buildList {
        when {
          sourceObservationTime != null -> add("Usage observed ${sourceObservationTime.ageLabel(now)}")
          value.source.capability != UsageCapability.SessionStatusOnly -> add("Provider observation time unavailable")
        }
        value.statusObservedAt?.takeIf { it != value.sourceObservationTime }?.let { add("Status updated ${it.ageLabel(now)}") }
        if (value.sourceObservationTime == null && value.statusObservedAt == null) add("Collected ${value.collectionTime.ageLabel(now)}; provider observation time unavailable")
      }.joinToString(" · ").ifBlank { "Observation time unavailable" },
      status = when {
        value.refreshState == UsageRefreshState.Loading -> if (value.meters.isEmpty()) "Loading usage" else "Checking for updates"
        value.refreshState == UsageRefreshState.Failed -> "Could not update usage"
        value.sessionWarning == true -> "Session usage warning"
        value.emptySuccess -> "No usage limits reported"
        else -> "Usage data available"
      },
      permission = value.requestPermission.uiName(),
      reason = value.refreshFailure?.let {
        listOf(it.presentationReason(value.source.provider, false), "Showing the previous observation").joinToString(". ")
      } ?: if (value.refreshState == UsageRefreshState.Failed) "Showing the previous observation" else null,
      cardMeters = value.cardCandidates.map { it.toUiMeter(now) },
      detailMeters = value.popupMeters.map { it.toUiMeter(now) },
      checking = value.refreshState == UsageRefreshState.Loading,
      stale = value.freshness == UsageFreshness.Stale || value.meters.any { it.stale },
      expired = value.meters.any { it.expired },
      sessionOnly = value.source.capability == UsageCapability.SessionStatusOnly,
    )
  }
  is UsageResult.Partial -> UiResult(
    source = source.provider.uiName(),
    scope = UsageObservationSubject.Account(subject).scopeLabel(),
    observedAt = sourceObservationTime?.let { "Usage observed ${it.ageLabel(now)}" } ?: "Provider observation time unavailable",
    status = "Usage data partially available",
    permission = requestPermission?.uiName(),
    detailMeters = fields.values.mapNotNull { (it as? PartialUsageField.Present)?.value?.toUiMeter(now) },
    checking = true,
  )
  is UsageResult.EmptySuccess -> UiResult(source.provider.uiName(), UsageObservationSubject.Account(subject).scopeLabel(), sourceObservationTime?.let { "Usage observed ${it.ageLabel(now)}" } ?: "Provider observation time unavailable", "No usage limits reported")
  is UsageResult.SignInRequired -> UiResult(status = "Sign-in required", reason = reason.presentationReason(selectedProvider, true))
  is UsageResult.Unsupported -> UiResult(status = "Usage unavailable", reason = reason.presentationReason(selectedProvider, false))
  is UsageResult.Unavailable -> UiResult(status = "Usage unavailable", reason = reason.presentationReason(selectedProvider, false))
  is UsageResult.TransientFailure -> UiResult(status = "Could not update usage", reason = reason.presentationReason(selectedProvider, false))
  is UsageResult.Throttled -> UiResult(status = "Refresh delayed", reason = reason.presentationReason(selectedProvider, false))
}

internal fun UsageMeter.toUiMeter(now: Instant): UsageUiMeter {
  val rendered = value.toUiValue()
  val fractionUsed = when (val value = value) {
    is UsageValue.PercentageUsed -> value.percent / 100.0
    is UsageValue.AmountUsed -> value.barFraction
    else -> null
  }
  val warning = fractionUsed != null && fractionUsed >= .85 && fractionUsed < 1.0
  val exhausted = fractionUsed != null && fractionUsed >= 1.0
  val stateText = when {
    expired -> "Expired"
    stale -> "Stale"
    exhausted -> "Limit reached"
    warning -> "Near limit"
    value is UsageValue.Unlimited -> "Unlimited"
    else -> null
  }
  val periodText = when (val period = period) {
    is UsagePeriod.Rolling -> "Rolling ${period.durationSeconds.periodLabel()}"
    is UsagePeriod.BillingBoundaries -> "Billing period"
    UsagePeriod.Unknown -> "Period not reported"
  }
  val scopeText = when (val meterScope = scope) {
    is UsageMeterScope.Model -> "Model ${meterScope.name}"
    is UsageMeterScope.Pool -> "Pool ${meterScope.name}"
    UsageMeterScope.General -> ""
  }
  val reset = resetAt?.let { deadline ->
    val remaining = (deadline - now).inWholeSeconds
    if (deadline <= now) "Reset time reached; awaiting a new observation (reported reset at $deadline)"
    else "Resets in ${remaining.coerceAtLeast(1).durationLabel()} (reported reset at $deadline)"
  }
  val meterAge = "Observed ${observedAt.ageLabel(now)}"
  return UsageUiMeter(
    id = bucketIdentity,
    label = listOf(label, scopeText).filter(String::isNotBlank).joinToString(" · "),
    detail = listOfNotNull(periodText, meterAge, reset).joinToString(" · "),
    valueText = rendered.first,
    progress = rendered.second,
    progressLabel = rendered.third?.let { "$periodText: $it" },
    progressStateDescription = listOfNotNull(
      if (stale) "Stale" else "Current",
      "Expired".takeIf { expired },
      stateText,
    ).distinct().joinToString(", "),
    stateText = stateText,
    warning = warning,
    exhausted = exhausted,
    stale = stale,
  )
}

private fun Long.periodLabel(): String = when {
  this % 3600 == 0L -> "${this / 3600}h"
  this >= 3600 -> "${this / 3600}h ${this % 3600 / 60}m"
  this % 60 == 0L -> "${this / 60}m"
  else -> "${this}s"
}

private fun UsageValue.toUiValue(): Triple<String, Float?, String?> = when (this) {
  is UsageValue.PercentageUsed -> Triple("$percent% used", percent.toFloat() / 100f, "Used $percent percent")
  is UsageValue.AmountUsed -> {
    val fraction = barFraction?.toFloat()
    Triple("${amount.display()} ${amount.unit} / ${limit.display()} ${limit.unit} used", fraction, "Used ${amount.display()} ${amount.unit} of ${limit.display()} ${limit.unit}")
  }
  is UsageValue.Money -> Triple(amount.display() + " " + currency, null, null)
  is UsageValue.CreditBalance -> Triple("Balance ${amount.display()} ${amount.unit}", null, null)
  UsageValue.Unlimited -> Triple("Unlimited", null, null)
  is UsageValue.Status -> Triple(label, null, null)
}

private fun DecimalQuantity.display(): String {
  val digits = unscaledDigits
  if (scale == 0) return digits
  val negative = digits.startsWith("-")
  val raw = digits.removePrefix("-").padStart(scale + 1, '0')
  val split = raw.length - scale
  return (if (negative) "-" else "") + raw.substring(0, split) + "." + raw.substring(split)
}

private fun Instant.ageLabel(now: Instant): String {
  val age = now - this
  if (age.isNegative()) return "at $this"
  val elapsed = age.inWholeSeconds
  val text = when {
    elapsed < 60 -> "$elapsed seconds ago"
    elapsed < 3600 -> "${elapsed / 60} minutes ago"
    elapsed < 86400 -> "${elapsed / 3600} hours ago"
    else -> "${elapsed / 86400} days ago"
  }
  return text
}

private fun Long.durationLabel(): String = when {
  this < 60 -> "$this seconds"
  this < 3600 -> "${this / 60} minutes"
  this < 86400 -> "${this / 3600} hours ${this % 3600 / 60} minutes"
  else -> "${this / 86400} days ${this % 86400 / 3600} hours"
}

private fun UsageUnavailableReason.presentationReason(provider: UsageProvider?, signInRequired: Boolean): String {
  val explanation = when (code) {
    UsageUnavailableCode.AwaitingCapabilityReader -> "A numeric usage reader is not available for this source"
    UsageUnavailableCode.UnsupportedPersonalSnapshot -> if (provider == UsageProvider.Cursor) "Cursor does not expose personal usage here; its plan is unknown" else "This source does not expose a personal usage snapshot here"
    UsageUnavailableCode.SessionObservationUnavailable -> if (provider == UsageProvider.Claude) "No live Claude session status is available" else "No live session status is available"
    UsageUnavailableCode.ProfileUnverified -> "Numeric account identity is not verified for this source"
    UsageUnavailableCode.ProviderUnavailable -> "The usage source is unavailable"
    UsageUnavailableCode.Throttled -> "The provider delayed this update"
    UsageUnavailableCode.TransientFailure -> "The usage update failed"
  }
  val action = when {
    signInRequired -> "Sign in is required. Rechecking does not establish numeric usage capability."
    provider == UsageProvider.Claude && code == UsageUnavailableCode.SessionObservationUnavailable -> "Session events update status; manual refresh is unavailable."
    else -> null
  }
  return listOfNotNull(explanation, detail?.takeIf(String::isNotBlank), action).joinToString(". ")
}

internal fun UsageUnavailableReason.refreshActionExplanation(provider: UsageProvider?, signInRequired: Boolean): String =
  if (!signInRequired && provider == UsageProvider.Claude && code == UsageUnavailableCode.SessionObservationUnavailable) {
    "Session events update status; manual refresh is unavailable."
  } else {
    presentationReason(provider, signInRequired)
  }

private fun RequestPermission.uiName() = when (this) {
  RequestPermission.Allowed -> "Allowed"
  RequestPermission.Rejected -> "Rejected"
  RequestPermission.Unknown -> "Unknown"
}

private fun UsageObservationSubject.scopeLabel(): String? = when (this) {
  is UsageObservationSubject.Account -> when (value.identity.workspace) {
    is VerifiedUsageWorkspaceScope.Workspace -> "Verified workspace"
    VerifiedUsageWorkspaceScope.VerifiedNonWorkspace, null -> "Verified account"
  }
  is UsageObservationSubject.Session -> null
}
