package skillbill.cli.phase

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import kotlin.test.Test
import kotlin.test.assertContains
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
  fun `verify is a usage error naming the verify operation`() {
    val error = assertFailsWith<UsageError> { PhaseInvocationParser.parse("verify", emptyList()) }

    assertContains(error.message.orEmpty(), "skill-bill operation verify")
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
      "Phase 'standalone' runs over durable workflow state; expected review, validation, qa, plan, pr, or monitor.",
      error.message,
    )
  }

  @Test
  fun `phase names are the standalone-invocable definitions, including the durable plan`() {
    assertEquals(listOf("review", "validation", "qa", "plan", "pr", "monitor"), PhaseInvocationParser.phaseNames())
    assertEquals(
      PhaseInvocation("plan", "SKILL-9 add a tab", null, null),
      PhaseInvocationParser.parse("plan", listOf("SKILL-9", "add", "a", "tab")),
    )
  }

  @Test
  fun `plan requires an intake while pr runs without one`() {
    val error = assertFailsWith<UsageError> { PhaseInvocationParser.parse("plan", listOf("mode:inline")) }

    assertTrue(error.message.orEmpty().startsWith("Phase 'plan' requires an intake"), error.message)
    assertEquals(PhaseInvocation("pr", null, null, null), PhaseInvocationParser.parse("pr", emptyList()))
  }

  @Test
  fun `stale owner recovery is accepted only for a monitor with an explicit target and a real owner token`() {
    val token = "owner-token-0123456789"
    val url = "https://github.com/acme/repo/pull/7"
    PhaseInvocationParser.requireRecoverableMonitor(PhaseInvocationParser.parse("monitor", listOf(url)), token)
    PhaseInvocationParser.requireRecoverableMonitor(PhaseInvocationParser.parse("monitor", listOf(url)), null)

    fun refusal(
      phase: String,
      rest: List<String>,
      candidate: String,
    ) = assertFailsWith<UsageError> {
      PhaseInvocationParser.requireRecoverableMonitor(PhaseInvocationParser.parse(phase, rest), candidate)
    }.message.orEmpty()

    assertContains(refusal("review", listOf("tighten"), token), "applies only to `phase monitor`")
    assertContains(refusal("monitor", emptyList(), token), "explicit pull request target")
    assertContains(refusal("monitor", listOf(url), "short"), "owner token a blocked monitor reported")
    assertContains(refusal("monitor", listOf(url), "owner token with spaces"), "owner token a blocked monitor reported")
  }
}
