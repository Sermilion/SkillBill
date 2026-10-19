package skillbill.engine.featuretask.runloop.finalization.standalone

import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.engine.featuretask.slot.state.StandaloneRepairWorktree
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal class StandaloneRepairCommit(
  private val git: WorkflowGitOperations,
  private val worktree: Path,
) : StandaloneRepairWorktree {
  override fun commitPending(subject: String): String? {
    val dirty =
      when (val result = git.repositoryOwnedPaths(worktree)) {
        is WorkflowGitNameListResult.Listed -> result
        is WorkflowGitNameListResult.Failed -> return "Could not discover repair changes: ${result.error}"
      }
    val staged =
      when (val result = git.stagedPaths(worktree)) {
        is WorkflowGitNameListResult.Listed -> result
        is WorkflowGitNameListResult.Failed -> return "Could not inspect the index: ${result.error}"
      }
    val paths = (dirty.names + staged.names).filterNot(::isRuntimePrivatePath).distinct().sorted()
    return if (paths.isEmpty()) null else stageAndCommit(paths, subject)
  }

  override fun headSha(): String? =
    (git.headCommitSha(worktree) as? WorkflowGitOperationResult.Ok)?.value?.trim()?.takeIf(String::isNotBlank)

  override fun descendsFrom(
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
    return when (val commit = git.createCommit(worktree, subject)) {
      is WorkflowGitCommitResult.Failed -> "Could not commit the repair: ${commit.error}"
      is WorkflowGitCommitResult.Committed, WorkflowGitCommitResult.NothingToCommit -> null
    }
  }
}
