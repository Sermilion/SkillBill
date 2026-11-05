package skillbill.ports.workflow.gitops

import skillbill.ports.workflow.gitops.history.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

object UnavailableCheckpointHistoryGitOperations : CheckpointHistoryGitOperations {
  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
    allowUnchangedIndex: Boolean,
  ): WorkflowGitOperationResult = unavailable("amend the HEAD commit")

  override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult =
    unavailable("read the HEAD commit message")

  override fun updateCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult = unavailable("write checkpoint ref '$refName'")

  override fun resolveCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult = unavailable("resolve checkpoint ref '$refName'")

  override fun listCheckpointRefs(
    repoRoot: Path,
    namespacePrefix: String,
  ): WorkflowGitNameListResult =
    WorkflowGitNameListResult.Failed(unavailableReason("list checkpoint refs under '$namespacePrefix'"))

  override fun deleteCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult = unavailable("delete checkpoint ref '$refName'")

  override fun deleteCheckpointRefsUnderPrefix(
    repoRoot: Path,
    namespacePrefix: String,
    subtaskRefPrefix: String,
  ): WorkflowGitOperationResult = unavailable("delete checkpoint refs under '$subtaskRefPrefix'")

  private fun unavailable(capability: String) = WorkflowGitOperationResult.Failed(error = unavailableReason(capability))

  private fun unavailableReason(capability: String) =
    "This git operations implementation cannot $capability; checkpoint history requires a git adapter."
}
