package dev.skillbill.desktop.core.data.usage.codex

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CodexUsageProtocolTest {
  @Test
  fun `preferred bucket map wins over compatibility data and retains each window`() {
    val value = Json.parseToJsonElement(
      """{"rateLimitsByLimitId":{"standard":{"primary":{"usedPercent":23.5,"windowDurationMins":300,"resetsAt":1800000000},"secondary":{"usedPercent":51,"windowDurationMins":10080}}},"rateLimits":{"ignored":{"primary":{"usedPercent":99,"windowDurationMins":1}}}}""",
    )

    val parsed = assertIs<CodexQuotaParse.Valid>(CodexUsageProtocol.parseQuota(value))

    assertEquals(1, parsed.buckets.size)
    assertEquals("standard", parsed.buckets.single().limitId)
    assertEquals(300, parsed.buckets.single().primary?.durationMinutes)
    assertEquals(1800000000, parsed.buckets.single().primary?.resetsAtUnixSeconds)
    assertEquals(10080, parsed.buckets.single().secondary?.durationMinutes)
  }

  @Test
  fun `invalid preferred view and invalid numeric windows do not fall back to stale compatibility values`() {
    val value = Json.parseToJsonElement(
      """{"rateLimitsByLimitId":{"standard":{"primary":{"usedPercent":101,"windowDurationMins":300}}},"rateLimits":{"standard":{"primary":{"usedPercent":20,"windowDurationMins":300}}}}""",
    )

    assertEquals(CodexQuotaParse.Invalid, CodexUsageProtocol.parseQuota(value))
  }

  @Test
  fun `valid empty data remains empty and not an allowance`() {
    val value = Json.parseToJsonElement("""{"rateLimitsByLimitId":{}}""")

    assertEquals(CodexQuotaParse.Valid(emptyList()), CodexUsageProtocol.parseQuota(value))
  }

  @Test
  fun `quoted numeric fields remain malformed rather than becoming quota evidence`() {
    for (window in listOf(
      """{"usedPercent":"20","windowDurationMins":300}""",
      """{"usedPercent":20,"windowDurationMins":"300"}""",
      """{"usedPercent":20,"windowDurationMins":300,"resetsAt":"1800000000"}""",
    )) {
      val value = Json.parseToJsonElement("""{"rateLimitsByLimitId":{"standard":{"primary":$window}}}""")

      assertEquals(CodexQuotaParse.Invalid, CodexUsageProtocol.parseQuota(value))
    }
  }
}
