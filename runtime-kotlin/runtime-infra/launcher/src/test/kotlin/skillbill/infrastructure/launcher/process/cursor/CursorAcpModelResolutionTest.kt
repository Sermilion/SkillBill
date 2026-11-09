package skillbill.infrastructure.launcher.process.cursor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CursorAcpModelResolutionTest {
  private val advertised =
    listOf(
      "default[]",
      "grok-4.7[context=256k,reasoning_effort=high,fast=true]",
      "grok-4.6[effort=high,fast=true]",
      "composer-2.5[fast=true]",
      "claude-opus-5-5[context=300k,effort=medium,fast=false]",
      "claude-opus-5[thinking=true,context=300k,effort=high,fast=false]",
      "gpt-5.3-codex[reasoning=medium,fast=false]",
      "gpt-5.6-sol[context=272k,reasoning=medium,fast=false]",
      "gemini-3.7-flash[effort=high]",
    )

  @Test
  fun `an advertised value is used as is`() {
    assertEquals("composer-2.5[fast=true]", resolveAcpModel("composer-2.5[fast=true]", advertised))
  }

  @Test
  fun `CLI slugs select the single advertised variant of their base model`() {
    val expected =
      mapOf(
        "composer-2.5" to "composer-2.5[fast=true]",
        "composer-2.5-fast" to "composer-2.5[fast=true]",
        "cursor-grok-4.6-high" to "grok-4.6[effort=high,fast=true]",
        "cursor-grok-4.6-low-fast" to "grok-4.6[effort=high,fast=true]",
        "grok-4.7-xhigh-fast" to "grok-4.7[context=256k,reasoning_effort=high,fast=true]",
        "claude-opus-5-5-high" to "claude-opus-5-5[context=300k,effort=medium,fast=false]",
        "claude-opus-5-thinking-high" to "claude-opus-5[thinking=true,context=300k,effort=high,fast=false]",
        "gpt-5.3-codex-high-fast" to "gpt-5.3-codex[reasoning=medium,fast=false]",
        "gpt-5.6-sol-xhigh" to "gpt-5.6-sol[context=272k,reasoning=medium,fast=false]",
        "gemini-3.7-flash-high" to "gemini-3.7-flash[effort=high]",
        "grok-4.6[effort=low]" to "grok-4.6[effort=high,fast=true]",
        "auto" to "default[]",
      )
    expected.forEach { (slug, variant) -> assertEquals(variant, resolveAcpModel(slug, advertised), slug) }
  }

  @Test
  fun `models whose base is not advertised stay unresolved`() {
    assertNull(resolveAcpModel("gpt-5.2", advertised))
    assertNull(resolveAcpModel("claude-opus-5-6-high", advertised))
    assertNull(resolveAcpModel("high", advertised))
  }

  @Test
  fun `an ambiguous base is not guessed`() {
    assertNull(resolveAcpModel("composer-2.5", listOf("composer-2.5[fast=true]", "composer-2.5[fast=false]")))
  }
}
