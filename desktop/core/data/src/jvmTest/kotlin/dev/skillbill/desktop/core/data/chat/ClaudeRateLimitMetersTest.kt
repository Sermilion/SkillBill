package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ClaudeRateLimitMetersTest {
  private val observedAt = Instant.fromEpochSeconds(1_794_140_000)

  private fun meters(info: String) = claudeRateLimitMeters(Json.parseToJsonElement(info).jsonObject, observedAt)

  // Shape captured from Claude Code 2.1.319: the card was empty because every window below was discarded.
  @Test
  fun `each unified window becomes a percentage meter with its reset and rolling period`() {
    val result = meters(
      """{"status":"allowed_warning","resetsAt":1794416400,"rateLimitType":"seven_day","utilization":0.81,""" +
        """"unifiedWindows":{"five_hour":{"utilization":0.07,"resetsAt":1794156000,"status":"allowed"},""" +
        """"seven_day":{"utilization":0.81,"resetsAt":1794416400,"status":"allowed_warning","surpassedThreshold":0.75}}}""",
    )

    assertEquals(listOf("claude:five_hour", "claude:seven_day"), result.map { it.bucketIdentity })
    val (fiveHour, weekly) = result
    assertEquals("5 hour", fiveHour.label)
    assertEquals<UsageValue?>(UsageValue.PercentageUsed.create(7.0), fiveHour.value)
    assertEquals<Instant?>(Instant.fromEpochSeconds(1_794_156_000), fiveHour.resetAt)
    assertEquals<UsagePeriod?>(UsagePeriod.Rolling.create(18_000), fiveHour.period)
    assertEquals("Weekly", weekly.label)
    assertEquals<UsageValue?>(UsageValue.PercentageUsed.create(81.0), weekly.value)
    assertEquals<UsagePeriod?>(UsagePeriod.Rolling.create(604_800), weekly.period)
    assertEquals(observedAt, weekly.observedAt)
  }

  @Test
  fun `a model-specific window keeps its base label and names the model`() {
    val meter = meters("""{"unifiedWindows":{"seven_day_opus":{"utilization":0.5,"resetsAt":1794416400}}}""").single()

    assertEquals("Weekly", meter.label)
    assertEquals(UsageMeterScope.Model("Opus"), meter.scope)
    assertEquals<UsagePeriod?>(UsagePeriod.Rolling.create(604_800), meter.period)
  }

  @Test
  fun `without unified windows the top-level window is used, and a window without utilization is skipped`() {
    assertEquals(
      listOf("claude:five_hour"),
      meters("""{"status":"allowed","rateLimitType":"five_hour","utilization":0.2,"resetsAt":1794156000}""").map { it.bucketIdentity },
    )
    assertEquals(emptyList(), meters("""{"status":"allowed","unifiedWindows":{"five_hour":{"status":"allowed"}}}"""))
    assertEquals(
      listOf("Weekly"),
      meters("""{"unifiedWindows":{"seven_day":{"utilization":0.4,"resetsAt":1794416400}}}""").map { it.label },
    )
    assertEquals(emptyList(), meters("""{"status":"allowed"}"""))
  }

  @Test
  fun `utilization above the limit is held at one hundred percent`() {
    assertEquals<UsageValue?>(UsageValue.PercentageUsed.create(100.0), meters("""{"unifiedWindows":{"five_hour":{"utilization":1.3}}}""").single().value)
  }
}
