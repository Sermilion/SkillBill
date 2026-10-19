package skillbill.engine.featuretask.runloop.finalization

import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal sealed interface InMemoryCommitPushResult {
  data class Pushed(val headSha: String, val committed: Boolean) : InMemoryCommitPushResult

  data class Failed(val error: String) : InMemoryCommitPushResult
}

internal class InMemoryCommitPush(
  private val git: WorkflowGitOperations,
  private val repoRoot: Path,
) {
  fun run(
    branch: String,
    issueKey: String,
  ): InMemoryCommitPushResult {
    val committed =
      when (val commit = commitPendingChanges(issueKey)) {
        is PendingCommit.Failed -> return InMemoryCommitPushResult.Failed(commit.error)
        is PendingCommit.Done -> commit.committed
      }
    val head = git.headCommitSha(repoRoot)
    if (head !is WorkflowGitOperationResult.Ok || head.value.isBlank()) {
      return InMemoryCommitPushResult.Failed("Could not resolve committed HEAD: ${head.error}")
    }
    val push = git.pushBranch(repoRoot, branch)
    return if (push is WorkflowGitOperationResult.Ok) {
      InMemoryCommitPushResult.Pushed(head.value.trim(), committed)
    } else {
      InMemoryCommitPushResult.Failed("Could not push branch '$branch': ${push.error}")
    }
  }

  private sealed interface PendingCommit {
    data class Done(val committed: Boolean) : PendingCommit

    data class Failed(val error: String) : PendingCommit
  }

  private fun commitPendingChanges(issueKey: String): PendingCommit {
    val stageable = stageableCommitPaths()
    if (stageable !is WorkflowGitOperationResult.Ok) return PendingCommit.Failed(stageable.error)
    val paths = stageable.value.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    return commitPaths(paths, issueKey)
  }

  private fun stageableCommitPaths(): WorkflowGitOperationResult {
    val staged = git.stagedPaths(repoRoot)
    val stagedRejection = stagedPathRejection(staged)
    if (stagedRejection != null) return WorkflowGitOperationResult.Failed(stagedRejection)
    val dirty = git.repositoryOwnedPaths(repoRoot)
    if (dirty is WorkflowGitNameListResult.Failed) {
      return WorkflowGitOperationResult.Failed("Could not discover pending changes: ${dirty.error}")
    }
    val discovered =
      ((dirty as WorkflowGitNameListResult.Listed).names + (staged as WorkflowGitNameListResult.Listed).names)
        .filterNot(::isRuntimePrivatePath).distinct().sorted()
    return withoutGitignoredFeatureSpecs(discovered)
  }

  private fun stagedPathRejection(staged: WorkflowGitNameListResult): String? =
    when (staged) {
      is WorkflowGitNameListResult.Failed -> "Could not inspect the index: ${staged.error}"
      is WorkflowGitNameListResult.Listed ->
        if (staged.names.any(::isRuntimePrivatePath)) {
          "Runtime-private files are staged. Unstage them before retrying the PR phase."
        } else {
          null
        }
    }

  private fun commitPaths(
    paths: List<String>,
    issueKey: String,
  ): PendingCommit {
    if (paths.isNotEmpty()) {
      val stage = git.stagePaths(repoRoot, paths)
      if (stage !is WorkflowGitOperationResult.Ok) {
        return PendingCommit.Failed("Could not stage pending changes: ${stage.error}")
      }
    }
    return when (val commit = git.createCommit(repoRoot, "$issueKey: Prepare changes for pull request")) {
      is WorkflowGitCommitResult.Failed -> PendingCommit.Failed("Could not commit pending changes: ${commit.error}")
      is WorkflowGitCommitResult.Committed -> PendingCommit.Done(committed = true)
      WorkflowGitCommitResult.NothingToCommit -> PendingCommit.Done(committed = false)
    }
  }

  private fun withoutGitignoredFeatureSpecs(paths: List<String>): WorkflowGitOperationResult {
    val ignored = git.gitignoredFeatureSpecPaths(repoRoot, paths)
    if (ignored !is WorkflowGitOperationResult.Ok) return ignored
    val ignoredSet = ignored.value.lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
    return WorkflowGitOperationResult.Ok(
      value = paths.filterNot { it in ignoredSet }.joinToString("\n"),
    )
  }
}
