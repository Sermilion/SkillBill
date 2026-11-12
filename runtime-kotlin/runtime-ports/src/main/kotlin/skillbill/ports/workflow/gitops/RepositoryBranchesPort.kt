package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.RepositoryBranchesResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

/**
 * Local branch reads and operator-requested checkout changes.
 *
 * The read blocks on git and must run off the UI thread and outside any database read.
 * It never contacts a remote, and every failure is returned as a typed result, never thrown.
 */
interface RepositoryBranchesPort {
  /** Lists local and remote-tracking branches of the repository containing [path], newest commit first. */
  fun branches(path: Path): RepositoryBranchesResult

  /**
   * Selects an existing worktree that holds [branch], or switches this checkout to it without discarding changes.
   * A remote-tracking [branch] such as `origin/feature` is checked out as a new local tracking branch.
   * Returns the selected checkout root in the success value, refusing a changed checkout identity.
   */
  fun switchToBranch(
    path: Path,
    branch: String,
    expectedCurrentBranch: String?,
  ): WorkflowGitOperationResult
}
