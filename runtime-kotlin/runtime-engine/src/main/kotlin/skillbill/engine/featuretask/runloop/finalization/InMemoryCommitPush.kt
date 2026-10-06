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
    val stageable = stageableCommitPaths()
    if (stageable !is WorkflowGitOperationResult.Ok) return stageable.error
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
      is WorkflowGitNameListResult.Listed -> {
        val actionable = remaining.names.filterNot(::isRuntimePrivatePath)
        val eligible = withoutGitignoredFeatureSpecs(actionable)
        if (eligible !is WorkflowGitOperationResult.Ok) {
          return "Could not read gitignored feature-spec paths: ${eligible.error}"
        }
        val leftover = eligible.value.lineSequence().map(String::trim).filter(String::isNotBlank)
        if (leftover.any()) {
          "Uncommitted changes remain after commit. Retry the PR phase to include them before publishing."
        } else {
          null
        }
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
