package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

const val GIT_PORCELAIN_MIN_LENGTH = 4
const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3

internal sealed interface DirtyPathsResult

internal data class DirtyPaths(
  val paths: List<String>,
  val omittedGitignoredFeatureSpecs: Boolean = false,
) : DirtyPathsResult

internal data class DirtyPathsError(val reason: String) : DirtyPathsResult

internal fun WorkflowGitOperations.dirtyImplementationPaths(repoRoot: Path): DirtyPathsResult {
  val status = worktreeStatus(repoRoot)
  if (status !is WorkflowGitOperationResult.Ok) {
    return DirtyPathsError("the worktree status could not be read before staging (${status.error})")
  }
  val paths =
    parseGitPorcelainPaths(status.value.orEmpty())
      .map(::normalizeRepoPath)
      .filter { it.isNotBlank() }
      .distinct()
      .sorted()
  val ignored = gitignoredFeatureSpecPaths(repoRoot, paths)
  if (ignored !is WorkflowGitOperationResult.Ok) {
    return DirtyPathsError("gitignored feature-spec paths could not be read before staging (${ignored.error})")
  }
  val ignoredSet =
    ignored.value.lineSequence().map(String::trim).filter(String::isNotBlank).toSet()
  return DirtyPaths(
    paths = paths.filterNot { it in ignoredSet },
    omittedGitignoredFeatureSpecs = ignoredSet.isNotEmpty(),
  )
}
