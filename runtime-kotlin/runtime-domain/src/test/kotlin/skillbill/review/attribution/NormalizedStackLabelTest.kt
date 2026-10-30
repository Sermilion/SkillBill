package skillbill.review.attribution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NormalizedStackLabelTest {
  private val packSlugs = listOf("kmp", "kotlin", "rust", "typescript")

  @Test
  fun `mixed kmp labels normalize to kmp while plain kotlin stays kotlin`() {
    val kmp = normalizeStackLabel("KMP/Kotlin", packSlugs)
    assertEquals("kmp", kmp.stack)
    assertFalse(kmp.fallback)
    assertNull(kmp.fallbackReason)
    assertEquals("kmp", normalizeStackLabel("Kotlin Multiplatform (KMP)", packSlugs).stack)
    val kotlin = normalizeStackLabel("Kotlin", packSlugs)
    assertEquals("kotlin", kotlin.stack)
    assertFalse(kotlin.fallback)
    assertNull(kotlin.fallbackReason)
  }

  @Test
  fun `descriptive labels resolve only to the passed pack slugs`() {
    assertEquals("rust", normalizeStackLabel("Rust", emptyList()).stack)
    assertEquals("unknown", normalizeStackLabel("Rust Toolchain", emptyList()).stack)
    assertEquals("rust", normalizeStackLabel("Rust Toolchain", packSlugs).stack)
  }

  @Test
  fun `the label token that comes first wins regardless of slug iteration order`() {
    assertEquals("rust", normalizeStackLabel("Rust and TypeScript", listOf("typescript", "rust")).stack)
    assertEquals("rust", normalizeStackLabel("Rust and TypeScript", listOf("rust", "typescript")).stack)
  }
}
