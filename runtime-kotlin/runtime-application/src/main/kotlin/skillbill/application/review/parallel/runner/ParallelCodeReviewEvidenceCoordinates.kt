package skillbill.application.review.parallel.runner

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.parallel.planning.ParallelCodeReviewRunnerPlanning
import skillbill.application.review.parallel.planning.hasSuppliedDiff
import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewEvidenceCoordinates

internal fun ParallelCodeReviewRunnerPlanning.evidenceCoordinates(
  request: ParallelCodeReviewRequest,
  head: String,
): ReviewEvidenceCoordinates {
  if (hasSuppliedDiff(request) || request.scope in setOf(ParallelReviewScope.BRANCH, ParallelReviewScope.PR)) {
    return ReviewEvidenceCoordinates.Committed(head)
  }
  val index =
    indexEntries(request.repoRoot)
      ?: throw DiffResolutionException("Cannot capture the reviewed index.")
  val entries =
    index.associate { entry ->
      if (entry.stage != 0) {
        throw DiffResolutionException("Review checkpoint contains unresolved index entries.")
      }
      entry.path to ReviewCheckpointFileIdentity.Regular(entry.objectId)
    }
  if (request.scope == ParallelReviewScope.STAGED) {
    return ReviewEvidenceCoordinates.Checkpoint(ReviewEvidenceCoordinates.Checkpoint.Kind.INDEX, entries)
  }
  val files = reviewWorktreeFileIdentities(request.repoRoot, entries.keys.toList())
  return ReviewEvidenceCoordinates.Checkpoint(ReviewEvidenceCoordinates.Checkpoint.Kind.WORKTREE, files)
}
