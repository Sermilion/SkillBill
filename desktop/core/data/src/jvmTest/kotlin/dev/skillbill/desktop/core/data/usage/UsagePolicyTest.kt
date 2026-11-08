package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.model.DecimalQuantity
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsageModelApplicability
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class UsagePolicyTest {
  @Test
  fun `ranking selects verified pool then lowest comparable remainder and stable identity`() {
    val selectedPool = meter("z-pool", UsageMeterScope.Pool("model-a"), UsageValue.PercentageUsed.create(5.0)!!)
      .copy(modelApplicability = UsageModelApplicability.Verified("model-a"))
    val lowest = meter("b-low", UsageMeterScope.General, amount("80", "100"))
    val tied = meter("a-tied", UsageMeterScope.General, amount("80", "100"))
    val money = meter("0-money", UsageMeterScope.General, UsageValue.Money.create(quantity("999", "USD"), "USD")!!)

    val ranked = UsageMeterRanking.rank(listOf(money, tied, selectedPool, lowest), "model-a")

    assertEquals(listOf("z-pool", "a-tied", "b-low", "0-money"), ranked.popupMeters.map { it.bucketIdentity })
    assertEquals(listOf("z-pool", "a-tied"), ranked.cardCandidates.map { it.bucketIdentity })
    assertEquals(
      listOf("a-tied", "b-low", "z-pool", "0-money"),
      UsageMeterRanking.rank(listOf(money, tied, selectedPool, lowest), null).popupMeters.map { it.bucketIdentity },
    )
  }

  @Test
  fun `ranking lists shorter allowance windows first`() {
    val weekly = meter("weekly", UsageMeterScope.General, UsageValue.PercentageUsed.create(90.0)!!)
      .copy(period = UsagePeriod.Rolling.create(7 * 24 * 3600)!!)
    val fiveHour = meter("five", UsageMeterScope.General, UsageValue.PercentageUsed.create(5.0)!!)
      .copy(period = UsagePeriod.Rolling.create(5 * 3600)!!)

    assertEquals(listOf("five", "weekly"), UsageMeterRanking.rank(listOf(weekly, fiveHour), null).cardCandidates.map { it.bucketIdentity })
  }

  @Test
  fun `refresh timing keeps freshness basis and caps jittered retry`() {
    val sourceFloor = 2.minutes
    assertEquals(2.minutes, UsageRefreshPolicy.expectedInterval(UsageProvider.Codex, UsageCapability.NumericSnapshot, true, sourceFloor))
    assertEquals(5.minutes, UsageRefreshPolicy.expectedInterval(UsageProvider.Codex, UsageCapability.NumericSnapshot, false, 0.seconds))
    assertNull(UsageRefreshPolicy.expectedInterval(UsageProvider.Claude, UsageCapability.NumericSnapshot, true, 0.seconds))
    assertNull(UsageRefreshPolicy.expectedInterval(UsageProvider.Codex, UsageCapability.Unsupported, true, 0.seconds))
    val observedAt = Instant.fromEpochMilliseconds(1_000)
    assertEquals(observedAt + 10.minutes, UsageRefreshPolicy.staleAt(observedAt, 5.minutes))
    assertEquals(30.seconds, UsageRefreshPolicy.retryDelay(1, 0.8))
    assertEquals(900.seconds, UsageRefreshPolicy.retryDelay(20, 1.2))
  }

  private fun meter(identity: String, scope: UsageMeterScope, value: UsageValue) = UsageMeter.create(
    identity,
    identity,
    scope,
    Instant.fromEpochMilliseconds(0),
    null,
    UsagePeriod.Unknown,
    value,
  )!!

  private fun amount(amount: String, limit: String) = UsageValue.AmountUsed.create(quantity(amount, "tokens"), quantity(limit, "tokens"))!!

  private fun quantity(value: String, unit: String) = DecimalQuantity.create(value, 0, unit)!!
}
