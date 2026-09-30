package skillbill.engine.featuretask.runloop.finalization

import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal class InMemoryCommitPush(
  private val git: WorkflowGitOperations,
  private val repoRoot: Path,
) {
  fun run(
    branch: String,
    issueKey: String,
  ): WorkflowGitOperationResult {
    commitPendingChanges(issueKey)?.let { return WorkflowGitOperationResult.Failed(it) }
    remainingChangesFailure()?.let { return WorkflowGitOperationResult.Failed(it) }
    val head = git.headCommitSha(repoRoot)
    if (head !is WorkflowGitOperationResult.Ok || head.value.isBlank()) {
      return WorkflowGitOperationResult.Failed("Could not resolve committed HEAD: ${head.error}")
    }
    val push = git.pushBranch(repoRoot, branch)
    return if (push is WorkflowGitOperationResult.Ok) {
      WorkflowGitOperationResult.Ok(head.value.trim())
    } else {
      WorkflowGitOperationResult.Failed("Could not push branch '$branch': ${push.error}")
    }
  }

  private fun commitPendingChanges(issueKey: String): String? {
    val staged = git.stagedPaths(repoRoot)
    if (staged is WorkflowGitNameListResult.Failed) return "Could not inspect the index: ${staged.error}"
    if ((staged as WorkflowGitNameListResult.Listed).names.any(::isRuntimePrivatePath)) {
      return "Runtime-private files are staged. Unstage them before retrying the PR phase."
    }
    val dirty = git.repositoryOwnedPaths(repoRoot)
    if (dirty is WorkflowGitNameListResult.Failed) return "Could not discover pending changes: ${dirty.error}"
    val paths =
      ((dirty as WorkflowGitNameListResult.Listed).names + staged.names)
        .filterNot(::isRuntimePrivatePath).distinct().sorted()
    return commitPaths(paths, issueKey)
  }

  private fun commitPaths(
    paths: List<String>,
    issueKey: String,
  ): String? {
    if (paths.isEmpty()) return null
    val stage = git.stagePaths(repoRoot, paths)
    if (stage !is WorkflowGitOperationResult.Ok) return "Could not stage pending changes: ${stage.error}"
    return when (val commit = git.createCommit(repoRoot, "$issueKey: Prepare changes for pull request")) {
      is WorkflowGitCommitResult.Failed -> "Could not commit pending changes: ${commit.error}"
      is WorkflowGitCommitResult.Committed, WorkflowGitCommitResult.NothingToCommit -> null
    }
  }

  private fun remainingChangesFailure(): String? =
    when (val remaining = git.repositoryOwnedPaths(repoRoot)) {
      is WorkflowGitNameListResult.Failed -> "Could not verify the worktree: ${remaining.error}"
      is WorkflowGitNameListResult.Listed ->
        if (remaining.names.any { !isRuntimePrivatePath(it) }) {
          "Uncommitted changes remain after commit. Retry the PR phase to include them before publishing."
        } else {
          null
        }
    }
}
