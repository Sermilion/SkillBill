package skillbill.infrastructure.sqlite.review.stats

import skillbill.infrastructure.sqlite.review.core.platformSlugFromRoutedSkill
import skillbill.infrastructure.sqlite.review.core.reviewPlatformSlug
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewPlatformSlugSupportTest {
  private val manifestMappings =
    mapOf(
      "bill-go-code-review" to "go",
      "bill-go-code-review-security" to "go",
      "bill-ios-code-review" to "ios",
      "bill-kmp-code-review" to "kmp",
      "bill-php-code-review" to "php",
      "bill-python-code-review" to "python",
      "bill-ruby-code-review" to "ruby",
    )

  @Test
  fun `routed review skills map from injected manifest-derived mappings`() {
    assertEquals("go", platformSlugFromRoutedSkill("bill-go-code-review", manifestMappings))
    assertEquals("go", platformSlugFromRoutedSkill("bill-go-code-review-security", manifestMappings))
    assertEquals("ios", platformSlugFromRoutedSkill("bill-ios-code-review", manifestMappings))
    assertEquals("php", platformSlugFromRoutedSkill("bill-php-code-review", manifestMappings))
    assertEquals("python", platformSlugFromRoutedSkill("bill-python-code-review", manifestMappings))
    assertEquals("ruby", platformSlugFromRoutedSkill("bill-ruby-code-review", manifestMappings))
  }

  @Test
  fun `detected stack keeps precedence over routed skill mapping`() {
    assertEquals(
      "kotlin",
      reviewPlatformSlug(
        detectedStack = "kotlin",
        routedSkill = "bill-python-code-review",
        routedSkillPlatformSlugs = manifestMappings,
      ),
    )
  }

  @Test
  fun `routed skill mapping wins over descriptive detected stack fingerprints`() {
    assertEquals(
      "kmp",
      reviewPlatformSlug(
        detectedStack = "Kotlin Multiplatform",
        routedSkill = "bill-kmp-code-review",
        routedSkillPlatformSlugs = manifestMappings,
      ),
    )
  }

  @Test
  fun `unknown routed skills and removed legacy prefixes stay unknown`() {
    assertEquals("unknown", platformSlugFromRoutedSkill(null, manifestMappings))
    assertEquals("unknown", platformSlugFromRoutedSkill("  ", manifestMappings))
    assertEquals("unknown", platformSlugFromRoutedSkill("bill-unrecognized-code-review", manifestMappings))
    assertEquals("unknown", platformSlugFromRoutedSkill("bill-go-code-review-fake", manifestMappings))
    assertEquals("unknown", platformSlugFromRoutedSkill("bill-agent-config-code-review", manifestMappings))
    assertEquals("unknown", platformSlugFromRoutedSkill("bill-android-code-review", manifestMappings))
  }
}
