package skillbill.engine.featuretask.slot.codereview

import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.slot.ReviewTarget
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.launch.CodeReviewExecutionMode
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

  private companion object {
    val BASE = "a".repeat(40)
    val HEAD = "b".repeat(40)
    val COMMIT = "c".repeat(40)
    val INPUT = GoalSubtaskReviewInput(BASE, HEAD, trackedDelta = "", ownedUntrackedPatches = "")
  }
}
