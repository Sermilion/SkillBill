package skillbill.review.parsing

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewRawAttributionPreservationTest {
  private fun review(routedSkillLine: String) =
    ReviewParser.parseReview(
      """
      Routed to: $routedSkillLine
      Review session ID: rvs-1
      Review run ID: rvw-1
      Detected review scope: branch diff
      Detected stack: kotlin

      ### 2. Risk Register
      - [F-001] Major | High | Auth.kt:12 | Token is logged with sensitive user data.
      """.trimIndent(),
    )

  @Test
  fun `a namespaced routed skill survives parsing without being rewritten`() {
    assertEquals("skillbill:bill-kotlin-code-review", review("skillbill:bill-kotlin-code-review").routedSkill)
  }

  @Test
  fun `a prose suffixed routed skill survives parsing without being truncated`() {
    val raw = "bill-kmp-code-review (persistence specialist)"

    assertEquals(raw, review(raw).routedSkill)
  }

  @Test
  fun `issue categorisation still sees the normalized routed skill`() {
    assertEquals("security_privacy", review("bill-code-review").findings.single().issueCategory)
    assertEquals(
      review("bill-code-review").findings.single().issueCategory,
      review("skillbill:bill-code-review").findings.single().issueCategory,
    )
  }
}
