package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
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
        .thenBy { comparableGroup(it) ?: "~incomparable:${it.bucketIdentity}" }
        .thenBy { remainingFraction(it) ?: Double.POSITIVE_INFINITY }
        .thenBy { it.bucketIdentity },
    )
    return RankedUsageMeters(ordered.take(2), ordered)
  }

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
