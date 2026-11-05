package skillbill.infrastructure.workflow.git.checkpoint

import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.withValue
import skillbill.ports.workflow.gitops.history.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

private const val REF_LISTING_PAIR_SIZE = 2

internal object GitCheckpointHistoryOperations : CheckpointHistoryGitOperations {
  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
    allowUnchangedIndex: Boolean,
  ): WorkflowGitOperationResult {
    val expected = expectedOwnedHeadSha.trim()
    val precondition =
      gitCheckpointProtectedBranchFailure(repoRoot)
        ?: gitCheckpointOwnedHeadFailure(repoRoot, expected)
        ?: if (allowUnchangedIndex) null else gitCheckpointStagedContentFailure(repoRoot, expected)
    precondition?.let { return it }
    val message = replacementMessage?.trim()
    val amendArgs =
      if (message.isNullOrBlank()) {
        listOf("commit", "--amend", "--no-edit")
      } else {
        listOf("commit", "--amend", "-m", message)
      }
    val amended = runGitCommand(repoRoot, amendArgs)
    if (amended !is WorkflowGitOperationResult.Ok) return amended
    return runGitCommand(repoRoot, "rev-parse", "HEAD")
  }

  override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult {
    val message = runGitCommand(repoRoot, "log", "-1", "--format=%B")
    if (message !is WorkflowGitOperationResult.Ok) return message
    return WorkflowGitOperationResult.Ok(value = message.value.orEmpty())
  }

  override fun updateCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult {
    val ref =
      gitCheckpointValidatedRef(namespacePrefix, refName)
        ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val target = targetSha.trim()
    if (target.isBlank()) {
      return WorkflowGitOperationResult.Failed(error = "A target sha is required to write ref '$ref'.")
    }
    return runGitCommand(repoRoot, "update-ref", ref, target).withValue(ref)
  }

  override fun resolveCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult {
    val ref =
      gitCheckpointValidatedRef(namespacePrefix, refName)
        ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val resolved = runGitCommand(repoRoot, "for-each-ref", "--format=%(objectname)", ref)
    if (resolved !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitOperationResult.Failed(
        error = "Ref '$ref' could not be looked up (${resolved.error}).",
      )
    }
    return WorkflowGitOperationResult.Ok(value = resolved.value.orEmpty().trim())
  }

  override fun listCheckpointRefs(
    repoRoot: Path,
    namespacePrefix: String,
  ): WorkflowGitNameListResult {
    val prefix = namespacePrefix.trim()
    if (prefix.isBlank()) {
      return WorkflowGitNameListResult.Failed("A ref namespace prefix is required.")
    }
    val listed =
      runGitCommand(
        repoRoot,
        "for-each-ref",
        "--format=%(objectname)%00%(refname)%00",
        prefix,
      )
    if (listed !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(listed.error)
    return WorkflowGitNameListResult.Listed(
      listed.value.orEmpty()
        .split('\u0000')
        .filter(String::isNotBlank)
        .chunked(REF_LISTING_PAIR_SIZE)
        .mapNotNull { pair -> pair.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) },
    )
  }

  override fun deleteCheckpointRefsUnderPrefix(
    repoRoot: Path,
    namespacePrefix: String,
    subtaskRefPrefix: String,
  ): WorkflowGitOperationResult {
    val names =
      when (val listed = listCheckpointRefs(repoRoot, subtaskRefPrefix)) {
        is WorkflowGitNameListResult.Listed -> listed.names
        is WorkflowGitNameListResult.Failed -> return WorkflowGitOperationResult.Failed(error = listed.error)
      }
    names.forEach { refName ->
      val deleted = deleteCheckpointRef(repoRoot, namespacePrefix, refName)
      if (deleted !is WorkflowGitOperationResult.Ok) return deleted
    }
    return WorkflowGitOperationResult.Ok(value = names.size.toString())
  }

  override fun deleteCheckpointRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
  ): WorkflowGitOperationResult {
    val ref =
      gitCheckpointValidatedRef(namespacePrefix, refName)
        ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", ref)
    if (existing !is WorkflowGitOperationResult.Ok || existing.value.orEmpty().isBlank()) {
      return WorkflowGitOperationResult.Ok(value = ref)
    }
    return runGitCommand(repoRoot, "update-ref", "-d", ref).withValue(ref)
  }
}
