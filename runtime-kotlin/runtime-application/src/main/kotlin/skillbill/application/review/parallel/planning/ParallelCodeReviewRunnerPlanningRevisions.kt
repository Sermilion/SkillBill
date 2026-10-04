package skillbill.application.review.parallel.planning

import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.parallel.runner.PARALLEL_REVIEW_HEAD_REVISION
import skillbill.application.reviewevidence.model.DiffResolution
import skillbill.application.reviewevidence.model.ParallelReviewScope
import java.nio.file.Path

internal fun ParallelCodeReviewRunnerPlanning.resolveReviewRevisions(
  request: ParallelCodeReviewRequest,
): DiffResolution<Pair<String, String>> {
  val range = if (spansCommitRange(request)) canonicalRange(request) else declaredRange(request)
  return when (range) {
    is DiffResolution.Unresolved -> range
    is DiffResolution.Resolved ->
      if (range.value.first.isBlank() || range.value.second.isBlank()) {
        DiffResolution.Unresolved("Review base and head revisions must resolve to non-blank immutable identities.")
      } else {
        range
      }
  }
}

internal fun DiffResolution.Unresolved.toPlanningFailed(): ParallelCodeReviewPlanned.Failed =
  ParallelCodeReviewPlanned.Failed(ParallelCodeReviewPlanningFailure.DiffUnresolved(message))

internal fun ParallelCodeReviewRunnerPlanning.spansCommitRange(request: ParallelCodeReviewRequest): Boolean =
  !hasSuppliedDiff(request) &&
    (request.scope == ParallelReviewScope.BRANCH || request.scope == ParallelReviewScope.PR)

internal fun ParallelCodeReviewRunnerPlanning.hasSuppliedDiff(request: ParallelCodeReviewRequest): Boolean =
  request.suppliedDiff != null || request.suppliedDiffPath != null

internal fun ParallelCodeReviewRunnerPlanning.canonicalRange(
  request: ParallelCodeReviewRequest,
): DiffResolution<Pair<String, String>> {
  val head =
    when (val resolved = canonicalRevision(request.headRevision ?: PARALLEL_REVIEW_HEAD_REVISION, request.repoRoot)) {
      is DiffResolution.Unresolved -> return resolved
      is DiffResolution.Resolved -> resolved.value
    }
  return when (val base = canonicalBase(request)) {
    is DiffResolution.Unresolved -> base
    is DiffResolution.Resolved -> DiffResolution.Resolved(base.value to head)
  }
}

private fun ParallelCodeReviewRunnerPlanning.canonicalBase(request: ParallelCodeReviewRequest): DiffResolution<String> =
  request.baseRevision?.let { canonicalRevision(it, request.repoRoot) } ?: when (request.scope) {
    ParallelReviewScope.PR -> detectPrBase(request.repoRoot)
    ParallelReviewScope.STAGED,
    ParallelReviewScope.UNSTAGED,
    ParallelReviewScope.UNCOMMITTED,
    ParallelReviewScope.BRANCH,
    ParallelReviewScope.WORKTREE_FROM_BASE,
    -> detectBranchBase(request.repoRoot)
  }

internal fun ParallelCodeReviewRunnerPlanning.declaredRange(
  request: ParallelCodeReviewRequest,
): DiffResolution<Pair<String, String>> {
  val head =
    when (val resolved = declaredHead(request)) {
      is DiffResolution.Unresolved -> return resolved
      is DiffResolution.Resolved -> resolved.value
    }
  return DiffResolution.Resolved((request.baseRevision ?: head) to head)
}

private fun ParallelCodeReviewRunnerPlanning.declaredHead(request: ParallelCodeReviewRequest): DiffResolution<String> =
  when {
    request.headRevision != null -> DiffResolution.Resolved(request.headRevision)
    hasSuppliedDiff(request) -> DiffResolution.Resolved(PARALLEL_REVIEW_HEAD_REVISION)
    else -> canonicalRevision(PARALLEL_REVIEW_HEAD_REVISION, request.repoRoot)
  }

internal fun ParallelCodeReviewRunnerPlanning.canonicalRevision(
  revision: String,
  repoRoot: Path,
): DiffResolution<String> =
  resolveCommit(repoRoot, revision)?.let { DiffResolution.Resolved(it) }
    ?: DiffResolution.Unresolved("Review revision '$revision' does not resolve to a commit here.")

internal fun ParallelCodeReviewRunnerPlanning.detectPrBase(repoRoot: Path): DiffResolution<String> =
  pullRequestBaseCommit(repoRoot)?.let { mergeBase(repoRoot, it) }?.let { DiffResolution.Resolved(it) }
    ?: detectBranchBase(repoRoot)

internal fun ParallelCodeReviewRunnerPlanning.detectBranchBase(repoRoot: Path): DiffResolution<String> {
  val candidates = listOf("main", "master", "origin/main", "origin/master")
  for (candidate in candidates) {
    mergeBase(repoRoot, candidate)?.let { return DiffResolution.Resolved(it) }
  }
  return DiffResolution.Unresolved("Could not detect branch base. Tried: ${candidates.joinToString()}.")
}
