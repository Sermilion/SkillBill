package skillbill.cli.codereview

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.cli.kernel.cli.DEFAULT_CODE_REVIEW_SCOPE
import skillbill.cli.kernel.cli.resolveStandaloneCodeReviewTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StandaloneCodeReviewTargetTest {
  @Test
  fun `positional pr reviews the open pull request`() {
    val target = resolveStandaloneCodeReviewTarget("pr", DEFAULT_CODE_REVIEW_SCOPE)

    assertEquals(ParallelReviewScope.PR, target.scope)
    assertEquals(null, target.commitRevision)
  }

  @Test
  fun `positional last reviews HEAD against its first parent`() {
    val target = resolveStandaloneCodeReviewTarget("last", DEFAULT_CODE_REVIEW_SCOPE)

    assertEquals(ParallelReviewScope.BRANCH, target.scope)
    assertEquals("HEAD", target.commitRevision)
  }

  @Test
  fun `positional commit sha reviews that commit against its first parent`() {
    val sha = "abc1234"
    val target = resolveStandaloneCodeReviewTarget(sha, DEFAULT_CODE_REVIEW_SCOPE)

    assertEquals(ParallelReviewScope.BRANCH, target.scope)
    assertEquals(sha, target.commitRevision)
  }

  @Test
  fun `positional uncommitted reviews the dirty worktree`() {
    val target = resolveStandaloneCodeReviewTarget("uncommitted", DEFAULT_CODE_REVIEW_SCOPE)

    assertEquals(ParallelReviewScope.UNCOMMITTED, target.scope)
    assertEquals(null, target.commitRevision)
  }

  @Test
  fun `scope option uncommitted is the same dirty-worktree packet`() {
    val target = resolveStandaloneCodeReviewTarget(null, "uncommitted")

    assertEquals(ParallelReviewScope.UNCOMMITTED, target.scope)
    assertEquals(null, target.commitRevision)
  }

  @Test
  fun `matching positional and scope tokens stay valid`() {
    val target = resolveStandaloneCodeReviewTarget("pr", "pr")

    assertEquals(ParallelReviewScope.PR, target.scope)
    assertEquals(null, target.commitRevision)
  }

  @Test
  fun `a commit sha cannot combine with a non-default scope`() {
    val error =
      assertFailsWith<UsageError> {
        resolveStandaloneCodeReviewTarget("abc1234", "pr")
      }

    assertEquals(
      "A commit target cannot be combined with --scope 'pr'; use the default branch scope.",
      error.message,
    )
  }

  @Test
  fun `conflicting positional and scope tokens fail`() {
    val error =
      assertFailsWith<UsageError> {
        resolveStandaloneCodeReviewTarget("pr", "uncommitted")
      }

    assertEquals(
      "A positional 'pr' cannot be combined with --scope 'uncommitted'.",
      error.message,
    )
  }
}
