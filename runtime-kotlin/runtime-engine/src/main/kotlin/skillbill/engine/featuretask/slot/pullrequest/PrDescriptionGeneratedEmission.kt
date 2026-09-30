package skillbill.engine.featuretask.slot.pullrequest

import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.slot.state.PhasePullRequestContext
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

internal class PrDescriptionGeneratedEmission(
  private val context: PhasePullRequestContext,
  private val measurement: PullRequestMeasurement,
) {
  fun emit(
    before: PullRequestIdentity,
    after: PullRequestIdentity?,
    branch: String,
    baseBranch: String,
  ) {
    runCatching { request(before, after, branch, baseBranch) }
      .onFailure { error ->
        RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, "$SKIPPED: measurement failed", error)
      }.getOrNull()
      ?.let(context.prDescriptionGenerated)
  }

  private fun request(
    before: PullRequestIdentity,
    after: PullRequestIdentity?,
    branch: String,
    baseBranch: String,
  ): PrDescriptionGeneratedRequest? {
    val found =
      after as? PullRequestIdentity.Found
        ?: return skip("$SKIPPED: no open pull request for '$branch' after the step")
    val created = measurement.created(before, found) as? Boolean ?: return null
    val (commits, files) = branchCounts(baseBranch) ?: return null
    return PrDescriptionGeneratedRequest(
      commitCount = commits,
      filesChangedCount = files,
      wasEditedByUser = false,
      prCreated = created,
      prTitle = found.title,
      orchestrated = false,
    )
  }

  private fun branchCounts(baseBranch: String): Pair<Int, Int>? {
    val repoRoot = context.request.repoRoot
    val git = context.gitOperations
    val base = "origin/$baseBranch"
    val commits =
      (git.commitCountAhead(repoRoot, base) as? WorkflowGitOperationResult.Ok)
        ?.value
        ?.trim()
        ?.toIntOrNull()
        ?: return skip("$SKIPPED: could not count commits ahead of $base")
    val forkPoint =
      (git.mergeBaseWithHead(repoRoot, base) as? WorkflowGitOperationResult.Ok)
        ?.value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return skip("$SKIPPED: could not find the merge base of $base and $HEAD")
    val files =
      git.runtimePhaseChangedPathsBetweenCommits(repoRoot, forkPoint, HEAD) as? WorkflowGitNameListResult.Listed
        ?: return skip("$SKIPPED: could not list files committed since the merge base with $base")
    return commits to files.names.size
  }

  private fun <T> skip(reason: String): T? {
    RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, reason)
    return null
  }

  private companion object {
    const val SKIPPED = "pr_description_generated skipped"
    const val HEAD = "HEAD"
  }
}
