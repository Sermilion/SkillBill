package skillbill.cli.phase

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PhaseInvocationParserTest {
  @Test
  fun `review parses intake text apart from its mode and target keys`() {
    val invocation = PhaseInvocationParser.parse("review", listOf("tighten", "errors", "mode:delegated", "target:HEAD"))

    assertEquals(
      PhaseInvocation(
        "review",
        "tighten errors",
        CodeReviewExecutionMode.DELEGATED.wireValue,
        ReviewTarget.Commit("HEAD"),
      ),
      invocation,
    )
  }

  @Test
  fun `intake words containing a colon stay intake`() {
    val invocation = PhaseInvocationParser.parse("review", listOf("check", "Foo.kt:12", "https://x.io", "mode:inline"))

    assertEquals("check Foo.kt:12 https://x.io", invocation.intake)
    assertEquals(CodeReviewExecutionMode.INLINE.wireValue, invocation.mode)
  }

  @Test
  fun `targets parse to uncommitted or a commit and an omitted target is left for the review to resolve`() {
    fun target(value: String) = PhaseInvocationParser.parse("review", listOf("target:$value")).target

    assertEquals(ReviewTarget.Uncommitted, target("uncommitted"))
    assertEquals(ReviewTarget.Commit("0123abc"), target("0123abc"))
    assertEquals(ReviewTarget.Commit("feat/SKILL-1"), target("feat/SKILL-1"))
    assertEquals(null, PhaseInvocationParser.parse("review", emptyList()).target)
  }

  @Test
  fun `last is an alias for HEAD rather than a revision named last`() {
    fun target(value: String) = PhaseInvocationParser.parse("review", listOf("target:$value")).target

    assertEquals(ReviewTarget.Commit("HEAD"), target("last"))
    assertEquals(ReviewTarget.Commit("HEAD"), target("LAST"))
  }

  @Test
  fun `commit_push is a usage error`() {
    val error = assertFailsWith<UsageError> { PhaseInvocationParser.parse("commit_push", emptyList()) }

    assertEquals("Phase 'commit_push' is not runnable on its own; run the full feature-task workflow.", error.message)
  }

  @Test
  fun `an unknown mode is a usage error naming inline and delegated`() {
    val error = assertFailsWith<UsageError> { PhaseInvocationParser.parse("review", listOf("mode:bogus")) }

    assertEquals("Unknown mode 'bogus'; expected inline or delegated (auto resolves inline).", error.message)
  }

  @Test
  fun `a blank target is a usage error`() {
    val error = assertFailsWith<UsageError> { PhaseInvocationParser.parse("review", listOf("target:")) }

    assertEquals(
      "Unknown target ''; expected HEAD, uncommitted, pr, staged, unstaged, or a commit sha, branch, or tag.",
      error.message,
    )
  }

  @Test
  fun `pr, staged, and unstaged targets parse to a scoped target with no revisions`() {
    fun target(value: String) = PhaseInvocationParser.parse("review", listOf("target:$value")).target

    assertEquals(ReviewTarget.Scoped(ParallelReviewScope.PR), target("pr"))
    assertEquals(ReviewTarget.Scoped(ParallelReviewScope.STAGED), target("staged"))
    assertEquals(ReviewTarget.Scoped(ParallelReviewScope.UNSTAGED), target("unstaged"))
  }

  @Test
  fun `a durable definition name is a usage error`() {
    val error = assertFailsWith<UsageError> { PhaseInvocationParser.parse("standalone", emptyList()) }

    assertEquals(
      "Phase 'standalone' runs over durable workflow state; expected review, validation, plan, or pr.",
      error.message,
    )
  }

  @Test
  fun `plan requires an intake while pr runs without one`() {
    val error = assertFailsWith<UsageError> { PhaseInvocationParser.parse("plan", listOf("mode:inline")) }

    assertTrue(error.message.orEmpty().startsWith("Phase 'plan' requires an intake"), error.message)
    assertEquals(PhaseInvocation("pr", null, null, null), PhaseInvocationParser.parse("pr", emptyList()))
  }
}
