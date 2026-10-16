package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal class StandaloneRepairCommit(
  private val git: WorkflowGitOperations,
  private val worktree: Path,
) {
  fun commitPending(subject: String): String? {
    val dirty = git.repositoryOwnedPaths(worktree)
    val staged = git.stagedPaths(worktree)
    if (dirty !is WorkflowGitNameListResult.Listed) return "Could not discover repair changes: ${failure(dirty)}"
    if (staged !is WorkflowGitNameListResult.Listed) return "Could not inspect the index: ${failure(staged)}"
    val paths = (dirty.names + staged.names).filterNot(::isRuntimePrivatePath).distinct().sorted()
    return if (paths.isEmpty()) null else stageAndCommit(paths, subject)
  }

  fun headSha(): String? =
    (git.headCommitSha(worktree) as? WorkflowGitOperationResult.Ok)?.value?.trim()?.takeIf(String::isNotBlank)

  fun descendsFrom(
    ancestorSha: String,
    descendantSha: String,
  ): Boolean =
    (git.isCommitAncestor(worktree, ancestorSha, descendantSha) as? WorkflowGitOperationResult.Ok)
      ?.value?.trim() == "true"

  private fun stageAndCommit(
    paths: List<String>,
    subject: String,
  ): String? {
    val stage = git.stagePaths(worktree, paths)
    if (stage !is WorkflowGitOperationResult.Ok) return "Could not stage the repair: ${stage.error}"
    val committed =
      when (val commit = git.createCommit(worktree, subject)) {
        is WorkflowGitCommitResult.Failed -> "Could not commit the repair: ${commit.error}"
        is WorkflowGitCommitResult.Committed, WorkflowGitCommitResult.NothingToCommit -> null
      }
    return committed ?: leftoverFailure()
  }

  private fun leftoverFailure(): String? =
    when (val remaining = git.repositoryOwnedPaths(worktree)) {
      is WorkflowGitNameListResult.Failed -> "Could not verify the worktree after the commit: ${remaining.error}"
      is WorkflowGitNameListResult.Listed ->
        remaining.names.filterNot(::isRuntimePrivatePath).takeIf { it.isNotEmpty() }
          ?.let { "Uncommitted changes remain after the repair commit: ${it.take(MAX_LISTED_PATHS).joinToString()}." }
    }

  private fun failure(result: WorkflowGitNameListResult): String = (result as WorkflowGitNameListResult.Failed).error

  private companion object {
    const val MAX_LISTED_PATHS = 5
  }
}
