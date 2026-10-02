package skillbill.application.review.parallel.planning

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.application.review.model.ParallelCodeReviewPlanned
import skillbill.application.review.model.ParallelCodeReviewPlanningFailure
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.parallel.runner.PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES
import skillbill.application.review.parallel.runner.ParallelCodeReviewStackDetection
import skillbill.application.reviewevidence.model.DiffResolution
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.install.model.SupportedAgent
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile

internal fun ParallelCodeReviewRunnerPlanning.resolveAgent(
  agentId: String,
  label: String,
): ParallelCodeReviewPlanned<SupportedAgent> {
  if (agentId.isBlank()) {
    return usageInvalid("Option $label is required. Supported agents: ${SupportedAgent.supportedIds.joinToString()}.")
  }
  val normalized = agentId.trim().lowercase()
  return SupportedAgent.entries.firstOrNull { it.id == normalized }?.let { ParallelCodeReviewPlanned.Ready(it) }
    ?: usageInvalid(
      "Unsupported agent '$agentId' for $label. Supported agents: ${SupportedAgent.supportedIds.joinToString()}.",
    )
}

private fun usageInvalid(message: String): ParallelCodeReviewPlanned.Failed =
  ParallelCodeReviewPlanned.Failed(ParallelCodeReviewPlanningFailure.UsageInvalid(message))

internal fun ParallelCodeReviewRunnerPlanning.resolveDiff(
  request: ParallelCodeReviewRequest,
  revisions: Pair<String, String>,
): DiffResolution<String> {
  if (request.suppliedDiff != null) {
    return DiffResolution.Resolved(request.suppliedDiff)
  }
  val (base, head) = revisions
  val diffText =
    when (val read = readScopeDiff(request, base, head)) {
      is DiffResolution.Unresolved -> return read
      is DiffResolution.Resolved -> read.value
    }
  if (diffText.isBlank() && request.scope != ParallelReviewScope.WORKTREE_FROM_BASE) {
    return DiffResolution.Unresolved("Diff is empty for scope '${request.scope.name.lowercase()}'.")
  }
  return DiffResolution.Resolved(diffText)
}

private fun ParallelCodeReviewRunnerPlanning.readScopeDiff(
  request: ParallelCodeReviewRequest,
  base: String,
  head: String,
): DiffResolution<String> {
  val suppliedPath = request.suppliedDiffPath
  if (suppliedPath != null) {
    return readDiff(suppliedPath, PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES)?.let { DiffResolution.Resolved(it) }
      ?: DiffResolution.Unresolved(
        "--diff-file must name a readable, non-empty regular file no larger than " +
          "$PARALLEL_REVIEW_MAX_SUPPLIED_DIFF_BYTES bytes.",
      )
  }
  return when (request.scope) {
    ParallelReviewScope.STAGED -> queryDiff(request, ReviewDiffQuery.Staged)
    ParallelReviewScope.UNSTAGED -> queryDiff(request, ReviewDiffQuery.Unstaged)
    ParallelReviewScope.UNCOMMITTED,
    ParallelReviewScope.WORKTREE_FROM_BASE,
    -> resolveWorktreeFromBaseDiff(request, base)
    ParallelReviewScope.BRANCH -> queryDiff(request, ReviewDiffQuery.CommitRange(base, head))
    ParallelReviewScope.PR -> queryDiff(request, ReviewDiffQuery.PullRequest(base, head))
  }
}

internal fun ParallelCodeReviewRunnerPlanning.resolveWorktreeFromBaseDiff(
  request: ParallelCodeReviewRequest,
  base: String,
): DiffResolution<String> {
  val tracked =
    when (
      val read =
        queryDiff(
          request,
          ReviewDiffQuery.WorkingTree(base, request.ownedPathspec, includeBinary = true),
        )
    ) {
      is DiffResolution.Unresolved -> return read
      is DiffResolution.Resolved -> read.value
    }
  val untracked =
    ownedUntrackedPaths(request)
      ?: return DiffResolution.Unresolved("Could not list untracked files for scope '${scopeName(request)}'.")
  val patches = StringBuilder()
  for (path in untracked) {
    val patch =
      diff(request.repoRoot, ReviewDiffQuery.UntrackedFile(path))
        ?: return DiffResolution.Unresolved("Could not read the diff of untracked file '$path'.")
    if (patch.isNotBlank()) {
      patches.append(patch)
      if (!patches.endsWith("\n")) patches.append('\n')
    }
  }
  return DiffResolution.Resolved(
    buildString {
      append(tracked)
      if (patches.isNotEmpty()) {
        if (isNotEmpty() && !endsWith("\n")) append('\n')
        append(patches)
      }
    },
  )
}

private fun ParallelCodeReviewRunnerPlanning.ownedUntrackedPaths(request: ParallelCodeReviewRequest): List<String>? {
  val excluded = request.baselineUntrackedPolicy.excludedPaths.toSet()
  return untrackedPaths(request.repoRoot)
    ?.map(String::trim)
    ?.filter(String::isNotBlank)
    ?.filterNot { it in excluded }
    ?.filter { path ->
      request.ownedPathspec.isEmpty() ||
        request.ownedPathspec.any { owned ->
          path == owned || path.startsWith("$owned/")
        }
    }
}

private fun scopeName(request: ParallelCodeReviewRequest): String = request.scope.name.lowercase()

private fun ParallelCodeReviewRunnerPlanning.queryDiff(
  request: ParallelCodeReviewRequest,
  query: ReviewDiffQuery,
): DiffResolution<String> =
  diff(request.repoRoot, query)?.let { DiffResolution.Resolved(it) }
    ?: DiffResolution.Unresolved("Could not read the diff for scope '${scopeName(request)}'.")

internal fun ParallelCodeReviewRunnerPlanning.detectStack(
  evidence: ReviewDiffEvidence,
): ParallelCodeReviewPlanned<ParallelCodeReviewStackDetection> {
  val manifests =
    runCatching { installedManifests() }
      .getOrElse { e ->
        e.rethrowIfCooperativeCancellationOrInterruption()
        return ParallelCodeReviewPlanned.Failed(
          ParallelCodeReviewPlanningFailure.StackUndetected(
            "Installed platform pack discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
              "Repair the installed platform packs before running parallel review.",
          ),
        )
      }
  if (manifests.isEmpty()) {
    return ParallelCodeReviewPlanned.Ready(ParallelCodeReviewStackDetection(emptyList(), emptyList(), emptyMap()))
  }

  val routing =
    ReviewStackRouting.route(
      manifests,
      evidence.files.map { ReviewRoutingChangedFile(it.path, it.changedContent) },
    )
  val routed = manifests.filter { it.slug in routing.routedSlugs }
  return ParallelCodeReviewPlanned.Ready(
    ParallelCodeReviewStackDetection(routed, manifests, routing.ownedPathsBySlug),
  )
}
