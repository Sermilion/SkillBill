package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.GIT_TIMEOUT_SECONDS
import skillbill.infrastructure.workflow.process.GitProcessResult
import skillbill.infrastructure.workflow.process.gitTimeoutSeconds
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

internal fun integrateRemoteAndPush(
  branch: String,
  sha: String,
  initialError: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  integrationRefusal(branch, execute)?.let { refusal ->
    recordRecovery("rejected", "remote_integration_refused")
    return WorkflowGitOperationResult.Failed("$initialError $refusal")
  }
  val fetchArgs = listOf("fetch", "origin", "+refs/heads/$branch:refs/remotes/origin/$branch")
  val fetched = execute(fetchArgs, gitTimeoutSeconds(fetchArgs))
  if (!fetched.succeeded) {
    recordRecovery("failed", "remote_integration_fetch_failed")
    return WorkflowGitOperationResult.Failed("$initialError Fetch of origin/$branch: ${fetched.failure}")
  }
  val rebaseArgs = listOf("rebase", "--no-autostash", "origin/$branch")
  val rebased = execute(rebaseArgs, gitTimeoutSeconds(rebaseArgs))
  return if (rebased.succeeded) {
    pushIntegratedBranch(branch, initialError, execute)
  } else {
    abortIntegration(branch, sha, initialError, rebased, execute)
  }
}

private fun pushIntegratedBranch(
  branch: String,
  initialError: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  val pushArgs = listOf("push", "-u", "origin", branch)
  val pushed = execute(pushArgs, gitTimeoutSeconds(pushArgs))
  if (!pushed.succeeded) {
    recordRecovery("failed", "remote_integration_push_failed")
    return WorkflowGitOperationResult.Failed(
      "$initialError Rebased '$branch' onto origin/$branch, but the push failed: ${pushed.failure}",
    )
  }
  recordRecovery("integrate_push", "remote_commits_integrated")
  return WorkflowGitOperationResult.Ok(branch)
}

private fun integrationRefusal(
  branch: String,
  execute: (List<String>, Long) -> GitProcessResult,
): String? {
  val current = execute(listOf("symbolic-ref", "--quiet", "--short", "HEAD"), GIT_TIMEOUT_SECONDS)
  if (!current.succeeded || current.output.trim() != branch) {
    return "Remote '$branch' has commits the local branch lacks, and '$branch' is not checked out to integrate them."
  }
  val status = execute(listOf("status", "--porcelain", "--untracked-files=no"), GIT_TIMEOUT_SECONDS)
  return when {
    !status.succeeded -> "Worktree status could not be read before integrating origin/$branch: ${status.failure}"
    status.output.isNotBlank() ->
      "Remote '$branch' has commits the local branch lacks, and uncommitted changes block integrating them."
    else -> null
  }
}

private fun abortIntegration(
  branch: String,
  sha: String,
  initialError: String,
  rebased: GitProcessResult,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  val conflicts = execute(listOf("diff", "--name-only", "--diff-filter=U"), GIT_TIMEOUT_SECONDS)
  val conflictPaths =
    if (conflicts.succeeded) {
      conflicts.output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    } else {
      emptyList()
    }
  execute(listOf("rebase", "--abort"), GIT_TIMEOUT_SECONDS)
  val restored = branchUnchanged(branch, sha, execute)
  val cause =
    if (conflictPaths.isEmpty()) {
      recordRecovery("rejected", "remote_integration_failed")
      "Rebase onto origin/$branch failed: ${rebased.failure}"
    } else {
      recordRecovery("rejected", "remote_integration_conflict")
      "Rebase onto origin/$branch conflicts in: ${conflictPaths.joinToString(", ")}"
    }
  val state =
    if (restored) {
      "The rebase was aborted and '$branch' is back at $sha."
    } else {
      "The rebase was aborted, but '$branch' is no longer at $sha; inspect it before retrying."
    }
  return WorkflowGitOperationResult.Failed("$initialError $cause. $state")
}
