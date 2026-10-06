package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import java.nio.file.Path

interface ScopedStagingGitOperations {
  fun stagePaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult

  fun captureIndexState(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitIndexSnapshotResult

  fun restoreIndexState(
    repoRoot: Path,
    paths: List<String>,
    snapshot: WorkflowGitIndexSnapshot,
  ): WorkflowGitOperationResult

  fun stagedPaths(repoRoot: Path): WorkflowGitNameListResult

  fun pathContentIdentities(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowPathContentIdentitiesResult

  /**
   * Feature-spec paths from [paths] that match a gitignore rule, including paths already in the index.
   * The ok value is a newline-separated list. Adapters that do not read gitignore return an empty list.
   */
  fun gitignoredFeatureSpecPaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult
}
