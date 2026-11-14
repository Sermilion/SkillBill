package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
import dev.skillbill.desktop.core.domain.usage.model.DecimalQuantity
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Hidden daily windows may contain placeholders. Only visible windows must parse. */
internal fun parseDevinUsage(body: String, at: Instant): UsageSnapshotRead {
  return try {
    val root = Json.parseToJsonElement(body).jsonObject
    if (root[DevinUsageKeys.IS_QUOTA_PLAN]?.jsonPrimitive?.booleanOrNull != true ||
      root[DevinUsageKeys.HAS_QUOTA_ALLOCATION]?.jsonPrimitive?.booleanOrNull != true) {
      UsageSnapshotRead.Failed
    } else {
      val hidden = root[DevinUsageKeys.HIDE_DAILY]?.jsonPrimitive?.booleanOrNull ?: return UsageSnapshotRead.Failed
      fun window(key: String, resetKey: String, label: String, seconds: Long): UsageMeter? {
        val value = root[key]?.jsonPrimitive?.doubleOrNull?.let(UsageValue.PercentageUsed::create) ?: return null
        val reset = root[resetKey]?.jsonPrimitive?.contentOrNull?.let(Instant::parse) ?: return null
        return UsageMeter.create(
          bucketIdentity = "devin:$key", label = label, scope = UsageMeterScope.General,
          observedAt = at, resetAt = reset, period = UsagePeriod.Rolling.create(seconds)!!, value = value,
          sourceOwner = UsageSource(UsageProvider.Devin, UsageCapability.NumericSnapshot), freshnessBasis = 5.minutes,
        )
      }
      val meters = mutableListOf<UsageMeter>()
      if (!hidden) meters += window(DevinUsageKeys.DAILY, DevinUsageKeys.DAILY_RESET, "Daily", 86400)
        ?: return UsageSnapshotRead.Failed
      meters += window(DevinUsageKeys.WEEKLY, DevinUsageKeys.WEEKLY_RESET, "Weekly", 7 * 86400)
        ?: return UsageSnapshotRead.Failed
      val balance = root[DevinUsageKeys.BALANCE]?.jsonPrimitive?.contentOrNull?.let(::BigDecimal)
        ?: return UsageSnapshotRead.Failed
      if (balance.signum() < 0 || balance.scale() !in -1000..1000 || balance.precision() > 1000) return UsageSnapshotRead.Failed
      val decimal = balance.stripTrailingZeros().toPlainString()
      val quantity = DecimalQuantity.create(decimal.replace(".", ""), decimal.substringAfter('.', "").length, "USD")
        ?: return UsageSnapshotRead.Failed
      meters += UsageMeter.create(
        bucketIdentity = "devin:${DevinUsageKeys.BALANCE}", label = "Extra balance", scope = UsageMeterScope.General,
        observedAt = at, resetAt = null, period = UsagePeriod.Unknown, value = UsageValue.CreditBalance(quantity),
        sourceOwner = UsageSource(UsageProvider.Devin, UsageCapability.NumericSnapshot), freshnessBasis = 5.minutes,
      )!!
      UsageSnapshotRead.Success(StoredUsageReading(RequestPermission.Unknown, false, at, meters))
    }
  } catch (_: IllegalArgumentException) {
    UsageSnapshotRead.Failed
  }
}
