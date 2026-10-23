package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import java.nio.file.Path

internal fun delegatedReviewRequest(
  agentId: String,
  repoRoot: Path,
  projectStoreRoot: Path,
  target: ReviewTarget,
  input: GoalSubtaskReviewInput,
): ParallelCodeReviewRequest {
  val (scope, base, head) =
    when (target) {
      ReviewTarget.LastCommit ->
        Triple(ParallelReviewScope.WORKTREE_FROM_BASE, input.reviewBaseSha, input.currentHeadSha)
      ReviewTarget.Uncommitted -> Triple(ParallelReviewScope.UNCOMMITTED, null, null)
      is ReviewTarget.Commit -> Triple(ParallelReviewScope.BRANCH, "${target.sha}^", target.sha)
      is ReviewTarget.Scoped -> Triple(target.scope, target.baseRevision, target.headRevision)
    }
  return ParallelCodeReviewRequest(
    agent1Id = agentId,
    scope = scope,
    repoRoot = repoRoot,
    projectStoreRoot = projectStoreRoot,
    timeout = null,
    codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    resolvedTier = CodeReviewExecutionMode.DELEGATED,
    suppliedDiffPath = (target as? ReviewTarget.Scoped)?.suppliedDiffPath,
    baseRevision = base,
    headRevision = head,
  )
}
