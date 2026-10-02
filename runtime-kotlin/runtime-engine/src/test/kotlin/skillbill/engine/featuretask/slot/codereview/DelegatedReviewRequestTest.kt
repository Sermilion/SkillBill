package skillbill.engine.featuretask.slot.codereview

import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DelegatedReviewRequestTest {
  @Test
  fun `each review target maps to its delegated scope and revisions`() {
    val cases =
      listOf(
        ReviewTarget.LastCommit to Triple(ParallelReviewScope.WORKTREE_FROM_BASE, BASE, HEAD),
        ReviewTarget.Uncommitted to Triple(ParallelReviewScope.UNCOMMITTED, null, null),
        ReviewTarget.Commit(COMMIT) to Triple(ParallelReviewScope.BRANCH, "$COMMIT^", COMMIT),
      )

    cases.forEach { (target, expected) ->
      val request = delegatedReviewRequest("claude", Path.of("/tmp/repo"), target, INPUT)

      assertEquals(expected, Triple(request.scope, request.baseRevision, request.headRevision), "$target")
      assertEquals(CodeReviewExecutionMode.DELEGATED, request.codeReviewMode, "$target")
    }
  }

  @Test
  fun `a scoped target carries its scope revisions and supplied diff unchanged`() {
    val diff = Path.of("/tmp/review.diff")
    val target = ReviewTarget.Scoped(ParallelReviewScope.STAGED, BASE, HEAD, diff)

    val request = delegatedReviewRequest("claude", Path.of("/tmp/repo"), target, INPUT)

    assertEquals(
      Triple(ParallelReviewScope.STAGED, BASE, HEAD),
      Triple(request.scope, request.baseRevision, request.headRevision),
    )
    assertEquals(diff, request.suppliedDiffPath)
  }

  @Test
  fun `the resolver reviews a dirty worktree uncommitted and a clean one at HEAD`() {
    assertEquals(ReviewTarget.Uncommitted, ReviewTargetResolver.resolve(null, " M src/Main.kt\n"))
    assertEquals(ReviewTarget.Commit("HEAD"), ReviewTargetResolver.resolve(null, ""))
    val requested = ReviewTarget.Scoped(ParallelReviewScope.PR)
    assertEquals(requested, ReviewTargetResolver.resolve(requested, " M src/Main.kt\n"))
  }

  private companion object {
    val BASE = "a".repeat(40)
    val HEAD = "b".repeat(40)
    val COMMIT = "c".repeat(40)
    val INPUT = GoalSubtaskReviewInput(BASE, HEAD, trackedDelta = "", ownedUntrackedPatches = "")
  }
}
