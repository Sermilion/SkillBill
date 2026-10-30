package dev.skillbill.desktop.app.architecture

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyRulesTest {
  @Test
  fun `no shell or feature source sorts work or reads a deadline`() {
    val desktopRoot = Path.of(requireNotNull(System.getProperty("desktopRoot")) { "The desktopRoot system property is not set." })
    assertEquals(emptyList(), PolicyRules.violations(PolicyRules.readSources(desktopRoot, loadManifests())))
  }

  @Test
  fun `an empty scan fails`() {
    assertTrue(PolicyRules.violations(emptyList()).isNotEmpty())
  }

  @Test
  fun `each pattern rejects a synthetic violation and ignores comments and strings`() {
    val violating = listOf(
      "items.sorted()", "items.sortedBy { it.title }", "items.sortedByDescending { it.title }", "items.sortedWith(order)",
      "items.sortedDescending()", "items.sortBy { it.title }", "items.sortByDescending { it.title }", "items.sortWith(order)",
      "items.sortDescending()", "item.membershipDeadline", "item.freshnessDeadline",
    )
    violating.forEach { code ->
      assertEquals(1, PolicyRules.violations(listOf(source("val x = $code"))).size, code)
    }
    val clean = source("// items.sorted()\nval label = \"items.sortedBy { it } membershipDeadline\"\n/* freshnessDeadline */")
    assertEquals(emptyList(), PolicyRules.violations(listOf(clean)))
  }

  private fun source(text: String) = ScannedSource("feature/work/src/commonMain/kotlin/Synthetic.kt", text)
}
