package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import java.nio.file.Path

internal fun WorkflowGitOperations.writeSubtaskCommitPreservingHistory(
  request: SubtaskCommitPreservationRequest,
): WorkflowGitOperationResult {
  if (request.decision !is FeatureTaskRuntimeSubtaskCommitAmend) {
    return when (val commit = createCommit(request.repoRoot, request.message)) {
      is WorkflowGitCommitResult.Committed -> WorkflowGitOperationResult.Ok(value = commit.commitSha)
      WorkflowGitCommitResult.NothingToCommit -> WorkflowGitOperationResult.Ok(value = "")
      is WorkflowGitCommitResult.Failed -> WorkflowGitOperationResult.Failed(error = commit.error)
    }
  }
  return amendSubtaskCommitPreservingHistory(this, request, request.decision)
}

private fun amendSubtaskCommitPreservingHistory(
  gitOperations: WorkflowGitOperations,
  request: SubtaskCommitPreservationRequest,
  decision: FeatureTaskRuntimeSubtaskCommitAmend,
): WorkflowGitOperationResult {
  if (decision.recoveredFromTrailer) {
    request.record(
      FeatureTaskRuntimeSubtaskCommitResolver.trailerFallbackRecord(
        request.identity,
        decision.ownedHeadSha,
      ),
    )
  }
  if (decision.rewritesPublishedHistory) {
    request.record(
      FeatureTaskRuntimeSubtaskCommitResolver.publishedHistoryRewriteRecord(request.identity, decision.ownedHeadSha),
    )
  }
  val refName = request.identity.checkpointRefName(decision.sequenceNumber)
  val preservation = preservePreAmendCheckpoint(gitOperations, request, refName, decision.ownedHeadSha)
  if (preservation != null) return preservation
  return gitOperations.amendHeadCommit(
    request.repoRoot,
    decision.ownedHeadSha,
    request.message,
    request.allowUnchangedIndex,
  )
}

private fun preservePreAmendCheckpoint(
  gitOperations: WorkflowGitOperations,
  request: SubtaskCommitPreservationRequest,
  refName: String,
  ownedHeadSha: String,
): WorkflowGitOperationResult? {
  val existing =
    gitOperations.resolveCheckpointRef(
      request.repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      refName,
    )
  if (existing !is WorkflowGitOperationResult.Ok) {
    return preAmendPreservationFailure(
      refName,
      "whether that ref already preserves another commit could not be determined (${existing.error})",
    )
  }
  val occupant = existing.value.orEmpty().trim()
  val sweepFailure = sweepForeignOccupant(gitOperations, request, refName, occupant, ownedHeadSha)
  if (sweepFailure != null) return sweepFailure
  val written =
    gitOperations.updateCheckpointRef(
      request.repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      refName,
      ownedHeadSha,
    )
  if (written !is WorkflowGitOperationResult.Ok) return preAmendPreservationFailure(refName, written.error)
  return verifyPreservedCheckpoint(gitOperations, request.repoRoot, refName, ownedHeadSha)
}

private fun sweepForeignOccupant(
  gitOperations: WorkflowGitOperations,
  request: SubtaskCommitPreservationRequest,
  refName: String,
  occupant: String,
  ownedHeadSha: String,
): WorkflowGitOperationResult? {
  if (occupant.isBlank() || occupant == ownedHeadSha) return null
  if (!request.allowUnchangedIndex) {
    return preAmendPreservationFailure(
      refName,
      "that ref already preserves '$occupant' and writing '$ownedHeadSha' over it would discard " +
        "the only reachability that commit has; the checkpoint sequence restarted, so this ref name is not " +
        "this checkpoint's to reuse",
    )
  }
  val prefix =
    "$FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE/" +
      "${request.identity.issueKey.trim()}/${request.identity.subtaskId}/"
  val swept =
    gitOperations.deleteCheckpointRefsUnderPrefix(
      request.repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      prefix,
    )
  if (swept !is WorkflowGitOperationResult.Ok) {
    return preAmendPreservationFailure(
      refName,
      "stale checkpoint refs under '$prefix' could not be swept before reclaiming the ref (${swept.error})",
    )
  }
  request.record(
    "seam=writeSubtaskCommitPreservingHistory value_used='swept ${swept.value.orEmpty()} stale checkpoint " +
      "ref(s) under $prefix (foreign occupant $occupant)' value_expected=checkpoint ref '$refName' free for " +
      "pre-amend '$ownedHeadSha' cause=commit_push finalisation reclaims the subtask checkpoint " +
      "namespace when a prior run left a foreign occupant",
  )
  return null
}

private fun verifyPreservedCheckpoint(
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
  refName: String,
  ownedHeadSha: String,
): WorkflowGitOperationResult? {
  val resolved =
    gitOperations.resolveCheckpointRef(
      repoRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      refName,
    )
  val preserved = resolved.value.orEmpty().trim()
  if (resolved !is WorkflowGitOperationResult.Ok || preserved != ownedHeadSha) {
    return preAmendPreservationFailure(
      refName,
      resolved.error.takeIf { it.isNotBlank() }
        ?: "the ref resolved to '$preserved' rather than the pre-amend commit '$ownedHeadSha'",
    )
  }
  return null
}

private fun preAmendPreservationFailure(
  refName: String,
  error: String,
) = WorkflowGitOperationResult.Failed(
  error =
    "the pre-amend checkpoint commit could not be preserved at '$refName' ($error); the amend " +
      "did not run and HEAD is unchanged",
)
