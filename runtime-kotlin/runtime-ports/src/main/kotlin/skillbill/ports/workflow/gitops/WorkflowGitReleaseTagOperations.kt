package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

/** The git reads and writes a release needs: the last semver tag, the log since it, and the annotated tag push. */
interface WorkflowGitReleaseTagOperations {
  /** The highest stable `vMAJOR.MINOR.PATCH` tag in the repository, or an empty value when there is none. */
  fun lastReleaseTag(repoRoot: Path): WorkflowGitOperationResult

  /** One line per commit from [sinceRef] (exclusive) to HEAD, or HEAD's whole history when [sinceRef] is null. */
  fun commitLogSince(
    repoRoot: Path,
    sinceRef: String?,
  ): WorkflowGitOperationResult

  /**
   * `true` when `origin/<branch>` holds commits HEAD lacks, else `false`. Reads the remote-tracking ref, so call
   * [WorkflowGitRemoteOperations.refreshRemoteBranch] first.
   */
  fun localBranchBehindRemote(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult

  /** The commit `origin/<branch>` points at, or [WorkflowGitRemoteOperations.ABSENT_REMOTE_BRANCH]. */
  fun remoteBranchHead(
    repoRoot: Path,
    branch: String,
  ): WorkflowGitOperationResult

  /** Creates annotated tag [tag] at HEAD whose message is exactly [message], byte for byte. */
  fun createAnnotatedTag(
    repoRoot: Path,
    tag: String,
    message: String,
  ): WorkflowGitOperationResult

  /** Pushes tag [tag] to origin. */
  fun pushTag(
    repoRoot: Path,
    tag: String,
  ): WorkflowGitOperationResult

  /** Deletes local tag [tag]; the remote is untouched. */
  fun deleteLocalTag(
    repoRoot: Path,
    tag: String,
  ): WorkflowGitOperationResult
}
