package dev.skillbill.desktop.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreplanDigestTest {
  @Test
  fun `legacy report headings cannot replace digest categories or invent evidence`() {
    val sections = digestSections("""
      SONA-19 PREPLAN DIGEST (feature_size MEDIUM, mode: decomposed).
      SCRIPTS / CONTRACTS / CONFIG:
      install.sh:80 and :2888.
    """.trimIndent())

    val rows = preplanDigestRows(sections)

    assertEquals(listOf("History and decisions", "Reusable patterns", "Add-ons", "Validation strategy"), rows.map { it.title })
    assertTrue(rows.all { it.items.isEmpty() && it.preview == "Not retained" })
    assertEquals(listOf("SONA-19 PREPLAN DIGEST (feature_size MEDIUM, mode: decomposed).", "install.sh:80 and :2888."), sections.flatMap { it.items })
  }

  @Test
  fun `canonical sections combine repeated headings and shorten addon names without losing text`() {
    val rows = preplanDigestRows(digestSections("""
      ## SELECTED ADD-ONS:
      bill-kotlin-code-review-persistence
      ## Reusable patterns
      Reuse the transaction owner.
      ## reusable_patterns
      Reuse the migration registry.
      ## Validation strategy
      Run populated-store migration tests.
    """.trimIndent()))

    assertEquals("2 found", rows[1].preview)
    assertEquals("kotlin · persistence", rows[2].preview)
    assertEquals(listOf("bill-kotlin-code-review-persistence"), rows[2].addons)
    assertEquals("Run populated-store migration tests.", rows[3].preview)
  }
}
