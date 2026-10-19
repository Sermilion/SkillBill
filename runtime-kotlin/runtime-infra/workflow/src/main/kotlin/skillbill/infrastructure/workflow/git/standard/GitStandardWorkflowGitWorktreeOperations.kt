package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.GitRepositoryFingerprintOperations
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.ports.workflow.gitops.worktree.WorkflowGitWorktreeOperations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

internal object GitStandardWorkflowGitWorktreeOperations : WorkflowGitWorktreeOperations {
  override fun stageAll(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "add", "-A")

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "status", "--porcelain", "-uall")

  override fun inProgressOperation(repoRoot: Path): WorkflowGitOperationResult {
    val markers = InProgressOperationMarker.entries
    val markerPaths =
      runGitCommand(repoRoot, listOf("rev-parse") + markers.flatMap { listOf("--git-path", it.gitPath) })
    if (markerPaths !is WorkflowGitOperationResult.Ok) return markerPaths
    val active =
      markers
        .zip(markerPaths.value.lines())
        .firstOrNull { (_, markerPath) -> markerPath.isNotBlank() && repoRoot.resolve(markerPath).exists() }
        ?.first
    return WorkflowGitOperationResult.Ok(value = active?.operation.orEmpty())
  }

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult =
    GitRepositoryFingerprintOperations.worktreeActivity(repoRoot)

  override fun worktreeNumstat(repoRoot: Path): WorkflowWorktreeNumstatResult =
    GitRepositoryFingerprintOperations.worktreeNumstat(repoRoot)

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = GitRepositoryFingerprintOperations.selectedDiffHunks(repoRoot, request)

  override fun linkedWorktreePath(
    repoRoot: Path,
    commonDirRelativePath: String,
  ): WorkflowGitOperationResult {
    val commonDir = runGitCommand(repoRoot, "rev-parse", "--path-format=absolute", "--git-common-dir")
    if (commonDir !is WorkflowGitOperationResult.Ok) return commonDir
    return WorkflowGitOperationResult.Ok(
      Path.of(commonDir.value.trim()).resolve(commonDirRelativePath).normalize().toString(),
    )
  }

  override fun addDetachedWorktree(
    repoRoot: Path,
    commonDirRelativePath: String,
    revision: String,
  ): WorkflowGitOperationResult {
    val resolved = linkedWorktreePath(repoRoot, commonDirRelativePath)
    if (resolved !is WorkflowGitOperationResult.Ok) return resolved
    val worktree = Path.of(resolved.value)
    val stale = removeWorktree(repoRoot, worktree)
    if (stale !is WorkflowGitOperationResult.Ok) return stale
    val added = runGitCommand(repoRoot, "worktree", "add", "--detach", worktree.toString(), revision)
    return if (added is WorkflowGitOperationResult.Ok) WorkflowGitOperationResult.Ok(worktree.toString()) else added
  }

  override fun removeWorktree(
    repoRoot: Path,
    worktree: Path,
  ): WorkflowGitOperationResult {
    if (Files.exists(worktree)) {
      val removed = runGitCommand(repoRoot, "worktree", "remove", "--force", worktree.toString())
      if (removed !is WorkflowGitOperationResult.Ok) return removed
    }
    return runGitCommand(repoRoot, "worktree", "prune")
  }
}

private enum class InProgressOperationMarker(val gitPath: String, val operation: String) {
  MERGE("MERGE_HEAD", "merge"),
  REBASE_MERGE("rebase-merge", "rebase"),
  REBASE_APPLY("rebase-apply", "rebase"),
  CHERRY_PICK("CHERRY_PICK_HEAD", "cherry-pick"),
  REVERT("REVERT_HEAD", "revert"),
}
