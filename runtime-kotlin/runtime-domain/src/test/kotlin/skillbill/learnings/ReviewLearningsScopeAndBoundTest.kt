package skillbill.learnings

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ReviewContextFailureCode
import skillbill.review.context.model.hunk.REVIEW_LEARNING_TITLE_MAX_CHARS
import skillbill.review.context.model.hunk.REVIEW_RULE_EXCERPT_MAX_CHARS
import skillbill.review.context.model.hunk.ReviewLearningsReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewLearningsScopeAndBoundTest {
  @Test fun `every origin URL form normalizes to the same owner and name scope key`() {
    listOf(
      "git@github.com:acme/repo.git",
      "git@github.com:acme/repo",
      "ssh://git@github.com/acme/repo.git",
      "https://github.com/acme/repo.git",
      "https://github.com/acme/repo",
      "https://github.com/acme/repo/",
      "  https://github.com/acme/repo.git  ",
    ).forEach { originUrl ->
      assertEquals("acme/repo", normalizeRepoScopeKey(originUrl), "Origin '$originUrl' must normalize.")
    }
  }

  @Test fun `a nested group origin keeps every path segment in its scope key`() {
    listOf(
      "git@gitlab.com:group/sub/repo.git",
      "https://gitlab.com/group/sub/repo",
    ).forEach { originUrl ->
      assertEquals("group/sub/repo", normalizeRepoScopeKey(originUrl), "Origin '$originUrl' must keep its group path.")
    }
  }

  @Test fun `an origin URL without an owner and name yields no scope key`() {
    listOf("", "   ", "github.com", "https://github.com", "git@github.com:repo.git")
      .forEach { originUrl ->
        assertNull(normalizeRepoScopeKey(originUrl), "Origin '$originUrl' must not produce a scope key.")
      }
  }

  @Test fun `a learning whose rule text exceeds the bounded projection limit fails loudly`() {
    val oversized = "x".repeat(REVIEW_RULE_EXCERPT_MAX_CHARS + 1)

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewLearningsReference(
          learningId = "L-001",
          source = "repo:acme/repo",
          scope = "repo",
          title = "Oversized rule",
          ruleText = oversized,
          digest = ReviewLearningsReference.digestOf(oversized),
        )
      }.also { assertEquals(ReviewContextFailureCode.LEARNING_RULE_TEXT_TOO_LONG, it.code) }

    assertTrue("L-001" in failure.message.orEmpty(), "The failure must name the offending learning.")
  }

  @Test fun `a learning whose title exceeds the bounded projection limit fails loudly`() {
    val ruleText = "Keep the boundary typed."

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        ReviewLearningsReference(
          learningId = "L-002",
          source = "repo:acme/repo",
          scope = "repo",
          title = "x".repeat(REVIEW_LEARNING_TITLE_MAX_CHARS + 1),
          ruleText = ruleText,
          digest = ReviewLearningsReference.digestOf(ruleText),
        )
      }.also { assertEquals(ReviewContextFailureCode.LEARNING_TITLE_TOO_LONG, it.code) }

    assertTrue("L-002" in failure.message.orEmpty(), "The failure must name the offending learning.")
  }
}
