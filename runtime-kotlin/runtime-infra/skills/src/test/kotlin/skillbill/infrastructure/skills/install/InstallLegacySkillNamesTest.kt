package skillbill.infrastructure.skills.install

import skillbill.infrastructure.skills.install.apply.legacySkillBillCacheNames
import skillbill.infrastructure.skills.install.apply.legacySkillBillCleanupNames
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class InstallLegacySkillNamesTest {
  @Test
  fun `retired feature skill keeps its link and mdp alias as cleanup names`() {
    val cleanupNames = legacySkillBillCleanupNames(listOf("skill-bill"))

    assertContains(cleanupNames, "bill-feature")
    assertContains(cleanupNames, "mdp-feature")
  }

  @Test
  fun `every catalog-retired skill and its mdp alias are cleanup names`() {
    val cleanupNames = legacySkillBillCleanupNames(listOf("skill-bill"))

    catalogRetiredSkillNames.forEach { name ->
      assertContains(cleanupNames, name)
      assertContains(cleanupNames, "mdp-${name.removePrefix("bill-")}")
    }
  }

  @Test
  fun `old names renamed into a now-retired skill are still cleanup names`() {
    val cleanupNames = legacySkillBillCleanupNames(listOf("skill-bill"))

    listOf("bill-feature-" + "implement", "bill-quality-check", "bill-gcheck", "bill-kotlin-feature-verify")
      .forEach { oldName -> assertContains(cleanupNames, oldName) }
  }

  @Test
  fun `cache cleanup names cover retired skills but never a live skill`() {
    val cacheNames = legacySkillBillCacheNames(listOf("skill-bill", "bill-code-review"))

    assertContains(cacheNames, "bill-feature")
    assertContains(cacheNames, "bill-quality-check")
    assertFalse("bill-code-review" in cacheNames)
    assertFalse(cacheNames.any { name -> name.startsWith("mdp-") })
  }

  private companion object {
    val catalogRetiredSkillNames =
      listOf(
        "bill-feature",
        "bill-feature-spec",
        "bill-code-review",
        "bill-code-review-inline",
        "bill-code-check",
        "bill-pr-description",
        "bill-boundary-history",
        "bill-boundary-decisions",
        "bill-pr-review-fix",
        "bill-unit-test-value-check",
        "bill-update-check",
        "bill-release",
        "bill-feature-verify",
        "bill-feature-guard",
        "bill-feature-guard-cleanup",
        "bill-monitor",
      )
  }
}
