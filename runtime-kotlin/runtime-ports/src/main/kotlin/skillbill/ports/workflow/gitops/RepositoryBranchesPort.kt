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

  /** Switches to the default branch without discarding changes, refusing a changed checkout identity. */
  fun switchToDefaultBranch(
    path: Path,
    expectedCurrentBranch: String?,
  ): WorkflowGitOperationResult
}
