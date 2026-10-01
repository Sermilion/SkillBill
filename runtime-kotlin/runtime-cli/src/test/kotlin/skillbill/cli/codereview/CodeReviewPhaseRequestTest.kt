package skillbill.cli.codereview

import com.github.ajalt.clikt.core.UsageError
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.cli.kernel.cli.DEFAULT_CODE_REVIEW_SCOPE
import skillbill.cli.kernel.cli.resolveStandaloneCodeReviewTarget
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodeReviewPhaseRequestTest {
  @Test
  fun `code-review flags build a review-definition request that carries them`() {
    val repoRoot = Path.of("/tmp/repo")

    val request =
      codeReviewPhaseRequest(
        CodeReviewFlags(
          agentId = "claude",
          repoRoot = repoRoot,
          target = resolveStandaloneCodeReviewTarget("abc1234", DEFAULT_CODE_REVIEW_SCOPE),
          executionMode = CodeReviewExecutionMode.DELEGATED.wireValue,
          reviewRunId = "rvw-20260927-120000-abcd",
          reviewSessionId = "rss-1",
        ),
      )

    assertEquals(SkeletonDefinition.REVIEW.id, request.definitionId)
    assertEquals(repoRoot, request.repoRoot)
    assertEquals(CodeReviewExecutionMode.DELEGATED, request.codeReviewMode)
    assertEquals(
      ReviewTarget.Scoped(ParallelReviewScope.BRANCH, "abc1234^", "abc1234"),
      request.reviewInvocation.target,
    )
    assertEquals("rvw-20260927-120000-abcd", request.reviewInvocation.reviewRunId)
    assertEquals("rss-1", request.reviewInvocation.reviewSessionId)
  }

  @Test
  fun `unpaired revisions and a diff file without revisions are usage errors`() {
    val flags =
      CodeReviewFlags(
        agentId = "claude",
        repoRoot = Path.of("/tmp/repo"),
        target = resolveStandaloneCodeReviewTarget(null, DEFAULT_CODE_REVIEW_SCOPE),
      )

    assertFailsWith<UsageError> { codeReviewPhaseRequest(flags.copy(baseRevision = "abc1234")) }
    assertFailsWith<UsageError> { codeReviewPhaseRequest(flags.copy(headRevision = "abc1234")) }
    assertFailsWith<UsageError> { codeReviewPhaseRequest(flags.copy(diffFile = "/tmp/review.diff")) }
  }

  @Test
  fun `inline mode rejects the delegated-only expansion and baseline-untracked flags`() {
    val flags =
      CodeReviewFlags(
        agentId = "claude",
        repoRoot = Path.of("/tmp/repo"),
        target = resolveStandaloneCodeReviewTarget(null, DEFAULT_CODE_REVIEW_SCOPE),
      )

    assertFailsWith<UsageError> { codeReviewPhaseRequest(flags.copy(baselineUntrackedIncludes = listOf("a.txt"))) }
    val auto = flags.copy(executionMode = CodeReviewExecutionMode.AUTO.wireValue)
    assertFailsWith<UsageError> { codeReviewPhaseRequest(auto.copy(expandFiles = listOf("x"))) }
  }
}
