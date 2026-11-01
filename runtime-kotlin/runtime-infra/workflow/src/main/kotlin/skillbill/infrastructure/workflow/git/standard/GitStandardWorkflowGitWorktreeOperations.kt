package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.fingerprint.GitRepositoryFingerprintOperations
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowIgnoredFilesCopyResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.ports.workflow.gitops.worktree.WorkflowGitWorktreeOperations
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

  override fun addDetachedWorktree(
    repoRoot: Path,
    worktree: Path,
    revision: String,
  ): WorkflowGitOperationResult {
    val stale = removeWorktree(repoRoot, worktree)
    if (stale !is WorkflowGitOperationResult.Ok) return stale
    val added = runGitCommand(repoRoot, "worktree", "add", "--detach", worktree.toString(), revision)
    return if (added is WorkflowGitOperationResult.Ok) WorkflowGitOperationResult.Ok(worktree.toString()) else added
  }

  override fun copyWorkingState(
    repoRoot: Path,
    worktree: Path,
  ): WorkflowGitOperationResult {
    val indexTree = runGitCommand(repoRoot, "write-tree")
    return indexTree
      .then { workingTreeObject(repoRoot, worktree) }
      .then { workingTree -> runGitCommand(worktree, "read-tree", "--reset", "-u", workingTree.trim()) }
      .then { runGitCommand(worktree, "read-tree", indexTree.value.trim()) }
      .then { matchingTreeIdentity(repoRoot, worktree) }
  }

  private fun matchingTreeIdentity(
    repoRoot: Path,
    worktree: Path,
  ): WorkflowGitOperationResult =
    GitRepositoryFingerprintOperations.repositoryTreeIdentity(repoRoot).then { source ->
      GitRepositoryFingerprintOperations.repositoryTreeIdentity(worktree).then { copy ->
        if (source.trim() == copy.trim()) {
          WorkflowGitOperationResult.Ok(worktree.toString())
        } else {
          WorkflowGitOperationResult.Failed(
            error = "the copy at $worktree does not match the tree identity of $repoRoot.",
          )
        }
      }
    }

  override fun copyIgnoredFiles(
    repoRoot: Path,
    worktree: Path,
    maxFileBytes: Long,
  ): WorkflowIgnoredFilesCopyResult {
    val listed =
      runGitCommand(repoRoot, "ls-files", "--others", "--ignored", "--exclude-standard", "--directory", "-z")
    if (listed !is WorkflowGitOperationResult.Ok) return WorkflowIgnoredFilesCopyResult.Failed(listed.error)
    val root = repoRoot.toAbsolutePath().normalize()
    val target = worktree.toAbsolutePath().normalize()
    val copied = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    return try {
      listed.value.split('\u0000').filter { it.isNotBlank() && !it.endsWith("/") }.sorted().forEach { path ->
        val source = root.resolve(path).normalize()
        val destination = target.resolve(path).normalize()
        val copyable =
          source.startsWith(root) && destination.startsWith(target) &&
            (
              Files.isSymbolicLink(source) ||
                (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && Files.size(source) <= maxFileBytes)
            )
        if (copyable) {
          destination.parent?.let(Files::createDirectories)
          Files.copy(source, destination, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
          copied += path
        } else {
          skipped += path
        }
      }
      WorkflowIgnoredFilesCopyResult.Copied(copied, skipped)
    } catch (failure: IOException) {
      WorkflowIgnoredFilesCopyResult.Failed("could not copy ignored files into $worktree: ${failure.message}")
    }
  }

  private fun workingTreeObject(
    repoRoot: Path,
    worktree: Path,
  ): WorkflowGitOperationResult {
    val index = runGitCommand(repoRoot, "rev-parse", "--path-format=absolute", "--git-path", "index")
    if (index !is WorkflowGitOperationResult.Ok) return index
    val adminDir = runGitCommand(worktree, "rev-parse", "--path-format=absolute", "--git-dir")
    if (adminDir !is WorkflowGitOperationResult.Ok) return adminDir
    val scratch = Path.of(adminDir.value.trim()).resolve(WORKING_STATE_INDEX)
    return try {
      val source = Path.of(index.value.trim())
      if (Files.exists(source)) Files.copy(source, scratch, StandardCopyOption.REPLACE_EXISTING)
      val environment = mapOf(GIT_INDEX_FILE to scratch.toString())
      val added = runGitCommand(repoRoot, environment, "add", "-A")
      if (added is WorkflowGitOperationResult.Ok) runGitCommand(repoRoot, environment, "write-tree") else added
    } catch (failure: IOException) {
      WorkflowGitOperationResult.Failed(error = "could not prepare a scratch index at $scratch: ${failure.message}")
    }
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

private inline fun WorkflowGitOperationResult.then(
  next: (String) -> WorkflowGitOperationResult,
): WorkflowGitOperationResult =
  when (this) {
    is WorkflowGitOperationResult.Ok -> next(value)
    is WorkflowGitOperationResult.Failed -> this
  }

private const val GIT_INDEX_FILE = "GIT_INDEX_FILE"
private const val WORKING_STATE_INDEX = "skill-bill-working-state.index"

private enum class InProgressOperationMarker(val gitPath: String, val operation: String) {
  MERGE("MERGE_HEAD", "merge"),
  REBASE_MERGE("rebase-merge", "rebase"),
  REBASE_APPLY("rebase-apply", "rebase"),
  CHERRY_PICK("CHERRY_PICK_HEAD", "cherry-pick"),
  REVERT("REVERT_HEAD", "revert"),
}
