package skillbill.review.attribution

import skillbill.review.model.ImportedFinding
import skillbill.review.parsing.ReviewParser
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewIssueCategoryTest {
  @Test
  fun `code quality specialist and aliases resolve without changing quality routing`() {
    val finding =
      ImportedFinding(
        findingId = "F-001",
        severity = "Minor",
        confidence = "High",
        location = "Example.kt:12",
        description = "A code quality observation.",
        findingText = "A code quality observation.",
      )

    assertEquals(
      "code_quality",
      resolveReviewIssueCategory(
        null,
        null,
        emptyList(),
        finding.copy(laneSkillName = "bill-generic-code-review-code-quality"),
      ),
    )
    assertEquals("code_quality", resolveReviewIssueCategory("code_quality", null, emptyList(), finding))
    assertEquals("code_quality", resolveReviewIssueCategory("code-quality", null, emptyList(), finding))
    assertEquals("testing_quality_gate", normalizeReviewIssueCategory("quality"))
    assertEquals(
      "testing_quality_gate",
      resolveReviewIssueCategory(null, "quality-check", emptyList(), finding),
    )
  }

  @Test
  fun `quality attribution classifies its finding without changing failure telemetry`() {
    listOf(
      "specialist=bill-generic-code-review-code-quality | Quality.kt:2 | Prefer a smaller helper",
      "Quality.kt:2 | Prefer a smaller helper | specialist=bill-generic-code-review-code-quality",
      "Quality.kt:2 | Prefer a smaller helper | specialists=bill-generic-code-review-code-quality",
    ).forEach { qualityBody ->
      val review =
        ReviewParser.parseReview(
          """
          Review run ID: rvw-test
          Review session ID: rvs-test
          Routed to: bill-generic-code-review
          Specialist reviews: bill-generic-code-review-code-quality,bill-generic-code-review-security
          - [F-001] Major | High | Auth.kt:1 | Token is logged with sensitive data
          - [F-002] Minor | High | $qualityBody
          """.trimIndent(),
        )

      assertEquals(listOf("security_privacy", "code_quality"), review.findings.map { it.issueCategory })
      assertEquals("Quality.kt:2", review.findings.last().location)
      assertEquals("bill-generic-code-review-code-quality", review.findings.last().laneSkillName)
      assertEquals("Prefer a smaller helper", review.findings.last().description)
    }
  }

  @Test
  fun `resolveReviewIssueCategory honors explicit routed classifier and fallback paths`() {
    val genericFinding =
      ImportedFinding(
        findingId = "F-001",
        severity = "Major",
        confidence = "High",
        location = "Auth.kt:12",
        description = "Token is logged with sensitive user data.",
        findingText = "Token is logged with sensitive user data.",
      )

    assertEquals(
      "data_persistence",
      resolveReviewIssueCategory("persistence", routedSkill = null, specialistReviews = emptyList(), genericFinding),
    )
    assertEquals(
      "testing_quality_gate",
      resolveReviewIssueCategory(null, routedSkill = null, specialistReviews = listOf("testing"), genericFinding),
    )
    assertEquals(
      "security_privacy",
      resolveReviewIssueCategory(
        explicitCategory = null,
        routedSkill = "bill-code-review",
        specialistReviews = emptyList(),
        finding = genericFinding,
      ),
    )
    assertEquals(
      "other",
      resolveReviewIssueCategory(
        explicitCategory = null,
        routedSkill = "bill-code-review",
        specialistReviews = emptyList(),
        finding =
          genericFinding.copy(
            location = "Example.kt:12",
            description = "Needs closer inspection.",
            findingText = "Needs closer inspection.",
          ),
      ),
    )
  }

  @Test
  fun `platform and scope dimensions normalize clean slugs without promoting prose`() {
    val slugs = listOf("kmp", "kotlin", "android")
    assertEquals("agent-config", normalizePlatformSlug("agent-config", slugs))
    assertEquals("kmp", normalizePlatformSlug("KMP", slugs))
    assertEquals("kmp", normalizePlatformSlug("KMP/Kotlin", slugs))
    assertEquals("kmp", normalizePlatformSlug("Kotlin Multiplatform (KMP)", slugs))
    assertEquals("kmp", normalizePlatformSlug("Kotlin multi-platform module", slugs))
    assertEquals("kotlin", normalizePlatformSlug(" kotlin ", slugs))
    assertEquals("kotlin", normalizePlatformSlug("backend kotlin", slugs))
    assertEquals("kotlin", normalizePlatformSlug("backend-kotlin", slugs))
    assertEquals("android", normalizePlatformSlug("android", slugs))
    assertEquals("unknown", normalizePlatformSlug(null, slugs))
    assertEquals("unknown", normalizePlatformSlug("Custom Stack!", slugs))

    assertEquals("branch_diff", normalizeScopeType("branch diff (main...HEAD)"))
    assertEquals("branch_diff", normalizeScopeType("branch_diff"))
    assertEquals("unstaged_changes", normalizeScopeType("unstaged changes"))
    assertEquals("files", normalizeScopeType("files"))
    assertEquals("custom_scope", normalizeScopeType("Custom Scope!"))
    assertEquals("unknown", normalizeScopeType(""))
  }
}
