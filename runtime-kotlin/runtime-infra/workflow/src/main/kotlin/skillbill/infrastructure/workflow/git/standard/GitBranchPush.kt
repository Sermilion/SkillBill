package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.GIT_TIMEOUT_SECONDS
import skillbill.infrastructure.workflow.process.GitProcessResult
import skillbill.infrastructure.workflow.process.gitTimedOutError
import skillbill.infrastructure.workflow.process.gitTimeoutSeconds
import skillbill.infrastructure.workflow.process.invokeGitProcess
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.gitops.ProtectedBranches
import java.nio.file.Path
import java.util.logging.Logger

private val pushRecoveryLog = Logger.getLogger("skillbill.infrastructure.workflow.git.standard.GitBranchPush")
private const val RECOVERY_PUSH_TIMEOUT_SECONDS = 45L
private const val GIT_FATAL_EXIT_CODE = 128
private const val REJECTED_MARKER = "[rejected]"

internal fun gitPushBranch(
  repoRoot: Path,
  branch: String,
  withLease: Boolean,
  execute: (List<String>, Long) -> GitProcessResult = { args, deadline ->
    invokeGitProcess(
      repoRoot,
      args,
      stdin = null,
      extraEnvironment = mapOf("GIT_TERMINAL_PROMPT" to "0"),
      deadlineSeconds = deadline,
    )
  },
): WorkflowGitOperationResult {
  val normalized = branch.trim()
  if (normalized.isBlank()) return WorkflowGitOperationResult.Failed("Branch name is required to push.")
  if (withLease) {
    ProtectedBranches.protectedName(normalized)?.let { protected ->
      return WorkflowGitOperationResult.Failed("Refusing to force-push protected branch '$protected'.")
    }
  }
  return pushResolvedBranch(normalized, withLease, execute)
}

private fun pushResolvedBranch(
  branch: String,
  withLease: Boolean,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  val head = execute(listOf("rev-parse", "--verify", "refs/heads/$branch^{commit}"), GIT_TIMEOUT_SECONDS)
  if (!head.succeeded) return WorkflowGitOperationResult.Failed("Could not resolve branch to push: ${head.failure}")
  val destination = execute(listOf("remote", "get-url", "--push", "--all", "origin"), GIT_TIMEOUT_SECONDS)
  if (!destination.succeeded) {
    return WorkflowGitOperationResult.Failed("Could not resolve push destination: ${destination.failure}")
  }
  val args =
    listOf("push") + (if (withLease) listOf("--force-with-lease") else emptyList()) +
      listOf("-u", "origin", branch)
  val initial = execute(args, gitTimeoutSeconds(args))
  if (initial.succeeded) return WorkflowGitOperationResult.Ok(branch)
  val initialError =
    if (initial.timedOut) {
      gitTimedOutError(args)
    } else {
      "git ${args.joinToString(" ")}: ${initial.failure}"
    }
  return if (!initial.timedOut && initial.readFailure == null && initial.exitCode != GIT_FATAL_EXIT_CODE) {
    if (initial.output.contains(REJECTED_MARKER)) {
      pushRewrittenHistory(branch, head.output.trim(), destination.output, initialError, execute)
    } else {
      WorkflowGitOperationResult.Failed(initialError)
    }
  } else {
    recoverPush(branch, head.output.trim(), destination.output, initialError, execute)
  }
}

private fun pushRewrittenHistory(
  branch: String,
  sha: String,
  destinations: String,
  initialError: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  val remote = integratedRemoteHead(branch, destinations, execute)
  if (remote !is WorkflowGitOperationResult.Ok) {
    return WorkflowGitOperationResult.Failed("$initialError ${remote.error}".trimEnd())
  }
  val args = listOf("push", "--force-with-lease=refs/heads/$branch:${remote.value}", "-u", "origin", branch)
  val lease = execute(args, gitTimeoutSeconds(args))
  val result =
    when {
      !lease.succeeded -> {
        recordRecovery("failed", "rewrite_lease_push_failed")
        WorkflowGitOperationResult.Failed("$initialError Lease push: ${lease.failure}")
      }
      !branchUnchanged(branch, sha, execute) ->
        WorkflowGitOperationResult.Failed(
          "$initialError Local branch moved during push; recovery preserved the new head.",
        )
      else -> WorkflowGitOperationResult.Ok(branch)
    }
  if (lease.succeeded) recordRecovery("lease_push", "local_history_rewritten")
  return result
}

private fun integratedRemoteHead(
  branch: String,
  destinations: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  val destination = destinations.lineSequence().filter(String::isNotBlank).toList().singleOrNull()
  if (ProtectedBranches.protectedName(branch) != null || destination == null) {
    return WorkflowGitOperationResult.Failed("")
  }
  val remote = remoteHead(destination, branch, execute)
  val remoteSha = (remote as? WorkflowGitOperationResult.Ok)?.value.orEmpty()
  val reflog = execute(listOf("rev-list", "--walk-reflogs", "refs/heads/$branch"), GIT_TIMEOUT_SECONDS)
  return when {
    remoteSha.isBlank() -> {
      recordRecovery("rejected", "rewrite_remote_unresolved")
      WorkflowGitOperationResult.Failed("Remote verification: ${remote.description}.")
    }
    !reflog.succeeded || remoteSha !in reflog.output.lineSequence().map(String::trim) -> {
      recordRecovery("rejected", "remote_commits_not_integrated")
      WorkflowGitOperationResult.Failed(
        "Remote head $remoteSha was never on local branch '$branch'; integrate it before pushing.",
      )
    }
    else -> WorkflowGitOperationResult.Ok(remoteSha)
  }
}

private fun recoverPush(
  branch: String,
  sha: String,
  destinations: String,
  initialError: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  recordRecovery("remote_verification", "uncertain_push_result")
  val destination = destinations.lineSequence().filter(String::isNotBlank).toList().singleOrNull()
  if (destination == null) {
    return WorkflowGitOperationResult.Failed("$initialError Recovery requires one origin push destination.")
  }
  val remote = remoteHead(destination, branch, execute)
  if (remote is WorkflowGitOperationResult.Ok && remote.value == sha) {
    return confirmPublished(branch, sha, destination, initialError, execute)
  }
  val rejection = retryRejection(branch, sha, destinations, execute)
  if (rejection != null) return WorkflowGitOperationResult.Failed("$initialError $rejection")
  return retryPush(branch, sha, destination, initialError, execute)
}

private fun retryRejection(
  branch: String,
  sha: String,
  destination: String,
  execute: (List<String>, Long) -> GitProcessResult,
): String? {
  if (!branchUnchanged(branch, sha, execute)) {
    return "Local branch moved during push; recovery preserved the new head."
  }
  val current = execute(listOf("remote", "get-url", "--push", "--all", "origin"), GIT_TIMEOUT_SECONDS)
  return if (!current.succeeded || current.output != destination) {
    "Origin push destination changed or could not be revalidated."
  } else {
    null
  }
}

private fun retryPush(
  branch: String,
  sha: String,
  destination: String,
  initialError: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  recordRecovery("normal_push_retry", "remote_not_confirmed")
  val retry = execute(listOf("push", "origin", "$sha:refs/heads/$branch"), RECOVERY_PUSH_TIMEOUT_SECONDS)
  if (retry.succeeded) return confirmPublished(branch, sha, destination, initialError, execute)
  val observed = remoteHead(destination, branch, execute)
  if (observed is WorkflowGitOperationResult.Ok && observed.value == sha) {
    return confirmPublished(branch, sha, destination, initialError, execute)
  }
  recordRecovery("failed", "recovery_exhausted")
  return WorkflowGitOperationResult.Failed(
    "$initialError Normal push retry: ${retry.failure}. Remote verification: ${observed.description}. " +
      "Local commit $sha is retained.",
  )
}

private fun remoteHead(
  destination: String,
  branch: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  val ref = "refs/heads/$branch"
  val remote = execute(listOf("ls-remote", "--refs", "--", destination, ref), GIT_TIMEOUT_SECONDS)
  if (!remote.succeeded) return WorkflowGitOperationResult.Failed(remote.failure)
  if (remote.output.isBlank()) return WorkflowGitOperationResult.Ok("")
  val fields = remote.output.trim().split('\t')
  return if (fields.size == 2 && fields[1] == ref && fields[0].matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) {
    WorkflowGitOperationResult.Ok(fields[0])
  } else {
    WorkflowGitOperationResult.Failed("Remote branch response could not be verified.")
  }
}

private fun confirmPublished(
  branch: String,
  sha: String,
  destination: String,
  initialError: String,
  execute: (List<String>, Long) -> GitProcessResult,
): WorkflowGitOperationResult {
  val rejection = retryRejection(branch, sha, destination, execute)
  if (rejection != null) {
    return WorkflowGitOperationResult.Failed("$initialError Publication confirmed. $rejection")
  }
  val tracking = execute(listOf("update-ref", "refs/remotes/origin/$branch", sha), GIT_TIMEOUT_SECONDS)
  if (!tracking.succeeded) {
    return WorkflowGitOperationResult.Failed(
      "$initialError Publication confirmed, but remote tracking could not be restored: ${tracking.failure}",
    )
  }
  val upstream = execute(listOf("branch", "--set-upstream-to=origin/$branch", "--", branch), GIT_TIMEOUT_SECONDS)
  if (!upstream.succeeded) {
    return WorkflowGitOperationResult.Failed(
      "$initialError Publication confirmed, but upstream could not be restored: ${upstream.failure}",
    )
  }
  recordRecovery("publication_confirmed", "remote_commit_verified")
  return WorkflowGitOperationResult.Ok(branch)
}

private fun branchUnchanged(
  branch: String,
  sha: String,
  execute: (List<String>, Long) -> GitProcessResult,
): Boolean {
  val current = execute(listOf("rev-parse", "--verify", "refs/heads/$branch^{commit}"), GIT_TIMEOUT_SECONDS)
  return current.succeeded && current.output.trim() == sha
}

private fun recordRecovery(
  actual: String,
  cause: String,
) {
  pushRecoveryLog.warning(
    "seam=git_push_recovery value_expected=push_acknowledged value_used=$actual cause=$cause",
  )
}

private val GitProcessResult.succeeded: Boolean
  get() = !timedOut && readFailure == null && exitCode == 0

private val GitProcessResult.failure: String
  get() =
    when {
      timedOut -> "timed out"
      readFailure != null -> "output read failed: ${readFailure.message.orEmpty()}"
      else -> "exit code $exitCode: $output"
    }

private val WorkflowGitOperationResult.description: String
  get() =
    when (this) {
      is WorkflowGitOperationResult.Ok -> if (value.isBlank()) "branch absent" else "remote head $value"
      is WorkflowGitOperationResult.Failed -> error
    }
