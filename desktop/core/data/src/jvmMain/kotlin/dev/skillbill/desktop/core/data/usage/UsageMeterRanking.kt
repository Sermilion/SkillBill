package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.domain.usage.model.UsageModelApplicability

internal data class RankedUsageMeters(
  val cardCandidates: List<UsageMeter>,
  val popupMeters: List<UsageMeter>,
)

internal object UsageMeterRanking {
  fun rank(meters: List<UsageMeter>, verifiedSelectedModel: String?): RankedUsageMeters {
    val ordered = meters.sortedWith(
      compareByDescending<UsageMeter> { isSelectedPool(it, verifiedSelectedModel) }
        .thenBy { periodSeconds(it.period) }
        .thenBy { comparableGroup(it) ?: "~incomparable:${it.bucketIdentity}" }
        .thenBy { it.scope != UsageMeterScope.General }
        .thenBy { remainingFraction(it) ?: Double.POSITIVE_INFINITY }
        .thenBy { it.bucketIdentity },
    )
    return RankedUsageMeters(ordered.take(2), ordered)
  }

  /** Shorter windows first, so a card reads "5 hour" before "Weekly" before "Monthly"; unknown periods go last. */
  private fun periodSeconds(period: UsagePeriod): Long = when (period) {
    is UsagePeriod.Rolling -> period.durationSeconds
    is UsagePeriod.BillingBoundaries -> BILLING_PERIOD_SECONDS
    UsagePeriod.Unknown -> Long.MAX_VALUE
  }

  private const val BILLING_PERIOD_SECONDS = 30L * 24 * 3600

  private fun isSelectedPool(meter: UsageMeter, selectedModel: String?) =
    selectedModel != null && meter.scope is UsageMeterScope.Pool &&
      meter.modelApplicability == UsageModelApplicability.Verified(selectedModel)

  private fun comparableGroup(meter: UsageMeter): String? = when (meter.value) {
    is UsageValue.PercentageUsed -> "percentage"
    is UsageValue.AmountUsed -> "amount:${valueUnit(meter.value)}:${meter.comparableAllowanceGroup.orEmpty()}"
    is UsageValue.Money,
    is UsageValue.CreditBalance,
    UsageValue.Unlimited,
    is UsageValue.Status -> null
  }

  private fun remainingFraction(meter: UsageMeter): Double? = when (val value = meter.value) {
    is UsageValue.PercentageUsed -> 1.0 - value.percent / 100.0
    is UsageValue.AmountUsed -> value.barFraction?.let { 1.0 - it }
    is UsageValue.Money,
    is UsageValue.CreditBalance,
    UsageValue.Unlimited,
    is UsageValue.Status -> null
  }

  private fun valueUnit(value: UsageValue): String = (value as UsageValue.AmountUsed).amount.unit
}
