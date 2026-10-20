package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun gitCheckoutCarryingUntracked(
  repoRoot: Path,
  targetRef: String?,
  checkout: () -> WorkflowGitOperationResult,
): WorkflowGitOperationResult {
  val colliding = targetRef?.let { collidingUntrackedPaths(repoRoot, it) }.orEmpty()
  if (colliding.isEmpty()) return checkout()
  val gitDir = runGitCommand(repoRoot, "rev-parse", "--absolute-git-dir")
  if (gitDir !is WorkflowGitOperationResult.Ok) return gitDir
  val carryRoot =
    Files.createTempDirectory(
      Files.createDirectories(Path.of(gitDir.value.trim()).resolve("skill-bill")),
      "checkout-carry-",
    )
  colliding.forEach { path -> movePath(repoRoot.resolve(path), carryRoot.resolve(path)) }
  val outcome = runCatching(checkout)
  colliding.forEach { path -> movePath(carryRoot.resolve(path), repoRoot.resolve(path)) }
  removeEmptyTree(carryRoot)
  return outcome.getOrThrow()
}

private fun collidingUntrackedPaths(
  repoRoot: Path,
  targetRef: String,
): List<String> {
  val untracked = runGitCommand(repoRoot, "ls-files", "--others", "--exclude-standard", "-z")
  if (untracked !is WorkflowGitOperationResult.Ok) return emptyList()
  val untrackedPaths = untracked.value.split('\u0000').filter(String::isNotBlank)
  if (untrackedPaths.isEmpty()) return emptyList()
  val tracked = runGitCommand(repoRoot, "ls-tree", "-r", "-z", "--name-only", targetRef)
  if (tracked !is WorkflowGitOperationResult.Ok) return emptyList()
  val targetPaths = tracked.value.split('\u0000').filter(String::isNotBlank).toSet()
  return untrackedPaths.filter(targetPaths::contains)
}

private fun movePath(
  source: Path,
  destination: Path,
) {
  Files.createDirectories(destination.parent)
  Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
}

private fun removeEmptyTree(root: Path) {
  Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }
    .forEach(Files::deleteIfExists)
}
