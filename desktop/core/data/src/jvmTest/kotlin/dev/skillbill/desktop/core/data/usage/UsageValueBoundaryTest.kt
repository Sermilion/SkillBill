package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.model.DecimalQuantity
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class UsageValueBoundaryTest {
  @Test
  fun `invalid bars stay unavailable while valid fractions exact units and periods survive`() {
    assertNull(UsageValue.PercentageUsed.create(Double.NaN))
    assertNull(UsageValue.PercentageUsed.create(101.0))

    val amount = DecimalQuantity.create("1250", 2, "tokens")!!
    assertNull(DecimalQuantity.create("12", 0, " "))
    val validLimit = DecimalQuantity.create("5000", 2, "tokens")!!
    val zeroLimit = DecimalQuantity.create("0", 0, "tokens")!!
    val wrongUnitLimit = DecimalQuantity.create("5000", 2, "requests")!!
    val negativeAmount = DecimalQuantity.create("-1", 0, "tokens")!!
    assertEquals(0.25, UsageValue.AmountUsed.create(amount, validLimit)!!.barFraction)
    assertNull(UsageValue.AmountUsed.create(amount, zeroLimit))
    assertNull(UsageValue.AmountUsed.create(amount, wrongUnitLimit))
    assertNull(UsageValue.AmountUsed.create(negativeAmount, validLimit))
    val overflowedAmount = DecimalQuantity.create("9".repeat(400), 0, "tokens")!!
    val overflowedLimit = DecimalQuantity.create("9".repeat(400), 0, "tokens")!!
    assertNull(UsageValue.AmountUsed.create(overflowedAmount, overflowedLimit)!!.barFraction)
    assertNull(UsagePeriod.Rolling.create(0))

    val observedAt = Instant.fromEpochMilliseconds(1_000)
    val period = UsagePeriod.Rolling.create(21_600)!!
    val meter = UsageMeter.create(
      "bucket-1",
      "Window 1",
      UsageMeterScope.General,
      observedAt,
      null,
      period,
      UsageValue.Money.create(DecimalQuantity.create("123456", 3, "USD")!!, "USD")!!,
    )!!
    assertEquals(21_600, (meter.period as UsagePeriod.Rolling).durationSeconds)
    val money = meter.value as UsageValue.Money
    assertEquals("123456", money.amount.unscaledDigits)
    assertEquals(3, money.amount.scale)
    assertEquals("USD", money.currency)
    assertNull(UsageValue.Money.create(money.amount, ""))
    assertNull(UsageValue.Money.create(money.amount, "EUR"))
  }
}
