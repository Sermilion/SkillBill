package dev.skillbill.desktop.core.datastore

import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import kotlin.time.Instant

/** One stored reading per provider profile. Only percentage meters with a rolling or unknown period are kept. */
internal data class StoredUsageEntry(
  val provider: UsageProvider,
  val configurationReference: String?,
  val reading: StoredUsageReading,
)

internal object UsageReadingEncoding {
  fun encode(entry: StoredUsageEntry): String = buildString {
    append("v1;")
    appendPart(entry.provider.wire())
    appendPart(if (entry.configurationReference == null) "0" else "1")
    appendPart(entry.configurationReference.orEmpty())
    appendPart(entry.reading.permission.name)
    appendPart(if (entry.reading.warning) "1" else "0")
    appendPart(entry.reading.observedAt.toEpochMilliseconds().toString())
    val meters = entry.reading.meters.mapNotNull(::meterParts)
    appendPart(meters.size.toString())
    meters.flatten().forEach { appendPart(it) }
  }

  fun decode(value: String): StoredUsageEntry? {
    if (!value.startsWith("v1;")) return null
    var offset = 3
    fun part(): String? {
      val separator = value.indexOf(';', offset)
      if (separator < 0) return null
      val length = value.substring(offset, separator).toIntOrNull() ?: return null
      if (length < 0) return null
      val start = separator + 1
      if (length > value.length - start) return null
      val end = start + length
      return value.substring(start, end).also { offset = end }
    }
    val provider = part()?.let { raw -> UsageProvider.entries.firstOrNull { it.wire() == raw } } ?: return null
    val hasReference = when (part()) {
      "1" -> true
      "0" -> false
      else -> return null
    }
    val rawReference = part() ?: return null
    val reference = rawReference.takeIf { hasReference }
    val permission = part()?.let { raw -> RequestPermission.entries.firstOrNull { it.name == raw } } ?: return null
    val warning = when (part()) {
      "1" -> true
      "0" -> false
      else -> return null
    }
    val observedAt = part()?.toLongOrNull()?.let(Instant::fromEpochMilliseconds) ?: return null
    val count = part()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val meters = List(count) {
      decodeMeter(List(METER_PARTS) { part() ?: return null }) ?: return null
    }
    if (offset != value.length) return null
    return StoredUsageEntry(provider, reference, StoredUsageReading(permission, warning, observedAt, meters))
  }

  private fun meterParts(meter: UsageMeter): List<String>? {
    val percent = (meter.value as? UsageValue.PercentageUsed)?.percent ?: return null
    val seconds = when (val period = meter.period) {
      is UsagePeriod.Rolling -> period.durationSeconds.toString()
      UsagePeriod.Unknown -> ""
      is UsagePeriod.BillingBoundaries -> return null
    }
    val (scopeKind, scopeName) = when (val scope = meter.scope) {
      UsageMeterScope.General -> "g" to ""
      is UsageMeterScope.Model -> "m" to scope.name
      is UsageMeterScope.Pool -> "p" to scope.name
    }
    return listOf(
      meter.bucketIdentity,
      meter.label,
      scopeKind,
      scopeName,
      meter.observedAt.toEpochMilliseconds().toString(),
      meter.resetAt?.epochSeconds?.toString().orEmpty(),
      seconds,
      percent.toString(),
    )
  }

  private fun decodeMeter(parts: List<String>): UsageMeter? {
    val (bucket, label, scopeKind, scopeName) = parts
    val scope = when (scopeKind) {
      "g" -> UsageMeterScope.General
      "m" -> UsageMeterScope.Model(scopeName)
      "p" -> UsageMeterScope.Pool(scopeName)
      else -> return null
    }
    val observedAt = parts[4].toLongOrNull()?.let(Instant::fromEpochMilliseconds) ?: return null
    val resetAt = parts[5].takeIf(String::isNotEmpty)?.let { it.toLongOrNull()?.let(Instant::fromEpochSeconds) ?: return null }
    val period = parts[6].takeIf(String::isNotEmpty)?.let { it.toLongOrNull()?.let(UsagePeriod.Rolling::create) ?: return null }
      ?: UsagePeriod.Unknown
    val value = parts[7].toDoubleOrNull()?.let(UsageValue.PercentageUsed::create) ?: return null
    return UsageMeter.create(bucket, label, scope, observedAt, resetAt, period, value)
  }

  private fun UsageProvider.wire() = name.lowercase()

  private fun StringBuilder.appendPart(value: String) {
    append(value.length)
    append(';')
    append(value)
  }

  private const val METER_PARTS = 8
}
