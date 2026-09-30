package skillbill.application.review.parallel.planning

import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.StackDetectionException
import skillbill.application.review.model.UsageValidationException
import skillbill.application.review.parallel.runner.PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES
import skillbill.application.review.parallel.runner.ParallelCodeReviewStackDetection
import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.install.model.SupportedAgent
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile

internal fun ParallelCodeReviewRunnerPlanning.resolveAgent(
  agentId: String,
  label: String,
): SupportedAgent {
  if (agentId.isBlank()) {
    throw UsageValidationException(
      "Option $label is required. Supported agents: ${SupportedAgent.supportedIds.joinToString()}.",
    )
  }
  return runCatching { SupportedAgent.fromNormalizedId(agentId, label = label) }
    .getOrElse {
      throw UsageValidationException(
        "Unsupported agent '$agentId' for $label. Supported agents: ${SupportedAgent.supportedIds.joinToString()}.",
      )
    }
}

internal fun ParallelCodeReviewRunnerPlanning.resolveDiff(
  request: ParallelCodeReviewRequest,
  revisions: Pair<String, String>,
): String {
  if (request.suppliedDiff != null) {
    return request.suppliedDiff
  }
  val (base, head) = revisions
  val diffText =
    request.suppliedDiffPath?.let { path ->
      readDiff(path, PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES)
        ?: throw DiffResolutionException(
          "--diff-file must name a readable, non-empty regular file no larger than " +
            "$PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES bytes.",
        )
    } ?: when (request.scope) {
      ParallelReviewScope.STAGED -> queryDiff(request, ReviewDiffQuery.Staged)
      ParallelReviewScope.UNSTAGED -> queryDiff(request, ReviewDiffQuery.Unstaged)
      ParallelReviewScope.UNCOMMITTED,
      ParallelReviewScope.WORKTREE_FROM_BASE,
      -> resolveWorktreeFromBaseDiff(request, base)
      ParallelReviewScope.BRANCH -> queryDiff(request, ReviewDiffQuery.CommitRange(base, head))
      ParallelReviewScope.PR -> queryDiff(request, ReviewDiffQuery.PullRequest(base, head))
    }
  if (diffText.isBlank() && request.scope != ParallelReviewScope.WORKTREE_FROM_BASE) {
    throw DiffResolutionException("Diff is empty for scope '${request.scope.name.lowercase()}'.")
  }
  return diffText
}

internal fun ParallelCodeReviewRunnerPlanning.resolveWorktreeFromBaseDiff(
  request: ParallelCodeReviewRequest,
  base: String,
): String {
  val tracked =
    queryDiff(
      request,
      ReviewDiffQuery.WorkingTree(base, request.ownedPathspec, includeBinary = true),
    )
  val excluded = request.baselineUntrackedPolicy.excludedPaths.toSet()
  val untracked =
    (
      untrackedPaths(request.repoRoot)
        ?: throw DiffResolutionException("Could not list untracked files for scope '${scopeName(request)}'.")
    )
      .map(String::trim)
      .filter(String::isNotBlank)
      .filterNot { it in excluded }
      .filter { path ->
        request.ownedPathspec.isEmpty() ||
          request.ownedPathspec.any { owned ->
            path == owned || path.startsWith("$owned/")
          }
      }
  val patches = StringBuilder()
  untracked.forEach { path ->
    val patch =
      diff(request.repoRoot, ReviewDiffQuery.UntrackedFile(path))
        ?: throw DiffResolutionException("Could not read the diff of untracked file '$path'.")
    if (patch.isNotBlank()) {
      patches.append(patch)
      if (!patches.endsWith("\n")) patches.append('\n')
    }
  }
  return buildString {
    append(tracked)
    if (patches.isNotEmpty()) {
      if (isNotEmpty() && !endsWith("\n")) append('\n')
      append(patches)
    }
  }
}

private fun scopeName(request: ParallelCodeReviewRequest): String = request.scope.name.lowercase()

private fun ParallelCodeReviewRunnerPlanning.queryDiff(
  request: ParallelCodeReviewRequest,
  query: ReviewDiffQuery,
): String =
  diff(request.repoRoot, query)
    ?: throw DiffResolutionException("Could not read the diff for scope '${scopeName(request)}'.")

internal fun ParallelCodeReviewRunnerPlanning.detectStack(
  evidence: ReviewDiffEvidence,
): ParallelCodeReviewStackDetection {
  val manifests =
    runCatching { installedManifests() }
      .getOrElse { e ->
        throw StackDetectionException(
          "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
            "Repair the installed platform packs before running parallel review.",
          e,
        )
      }
  if (manifests.isEmpty()) return ParallelCodeReviewStackDetection(emptyList(), emptyList(), emptyMap())

  val routing =
    ReviewStackRouting.route(
      manifests,
      evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) },
    )
  val routed = manifests.filter { it.slug in routing.routedSlugs }
  return ParallelCodeReviewStackDetection(routed, manifests, routing.ownedPathsBySlug)
}
