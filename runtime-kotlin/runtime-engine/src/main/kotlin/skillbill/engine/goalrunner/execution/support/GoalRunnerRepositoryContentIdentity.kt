package skillbill.engine.goalrunner.execution.support

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

internal fun goalRunnerRepositoryContentIdentity(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): WorkflowGitOperationResult {
  val tracked = gitOperations.trackedPaths(repoRoot)
  if (tracked !is WorkflowGitNameListResult.Listed) {
    return WorkflowGitOperationResult.Failed(error = "Could not enumerate tracked repository paths.")
  }
  val owned = gitOperations.repositoryOwnedPaths(repoRoot)
  if (owned !is WorkflowGitNameListResult.Listed) {
    return WorkflowGitOperationResult.Failed(error = "Could not enumerate untracked repository paths.")
  }
  val paths = (tracked.names + owned.names).distinct().filterNot(::isFeatureSpecPath)
  return gitOperations.scopedContentFingerprint(repoRoot, paths)
}
