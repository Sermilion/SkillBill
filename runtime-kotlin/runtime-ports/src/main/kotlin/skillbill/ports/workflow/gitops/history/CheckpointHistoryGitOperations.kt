package skillbill.ports.workflow.gitops.history

import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

interface CheckpointHistoryGitOperations {
  fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String? = null,
    allowUnchangedIndex: Boolean = false,
  ): WorkflowGitOperationResult

  fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult

  fun updateCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult

  fun resolveCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult

  fun listCheckpointRefs(
    repoRoot: Path,
    namespacePrefix: String,
  ): WorkflowGitNameListResult

  fun deleteCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult

  /** Deletes every ref under [subtaskRefPrefix]; the value carries the deleted ref count. */
  fun deleteCheckpointRefsUnderPrefix(
    repoRoot: Path,
    namespacePrefix: String,
    subtaskRefPrefix: String,
  ): WorkflowGitOperationResult
}
