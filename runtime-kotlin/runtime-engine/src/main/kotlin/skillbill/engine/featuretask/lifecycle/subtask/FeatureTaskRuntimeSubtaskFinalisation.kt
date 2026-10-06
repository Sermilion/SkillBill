package skillbill.engine.featuretask.lifecycle.subtask

import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.SupersededCheckpointPromoter
import skillbill.engine.featuretask.lifecycle.checkpoint.pruneSubtaskCheckpointRefs
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointRefPruneRequest
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoffResult
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalisationResult
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalised
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

class FeatureTaskRuntimeSubtaskFinalisation(
  val gitOperations: WorkflowGitOperations,
  val repoRoot: Path,
  val record: (String) -> Unit,
  val recordCommit: (commitSha: String, stagedPaths: List<String>) -> String?,
) {
  fun finalise(request: FeatureTaskRuntimeSubtaskFinaliseRequest): FeatureTaskRuntimeSubtaskFinalisationResult {
    val dirtyOrError = gitOperations.dirtyImplementationPaths(repoRoot)
    if (dirtyOrError is DirtyPathsError) return blocked(dirtyOrError.reason)
    val dirty = dirtyOrError as DirtyPaths
    if (dirty.paths.isEmpty() && dirty.omittedGitignoredFeatureSpecs) return publishUnchangedHead(request)
    val paths = dirty.paths
    val staging =
      when (val outcome = prepareStaging(paths)) {
        is FinalisationStagingBlocked -> return outcome.result
        is FinalisationStagingReady -> outcome
      }
    return commitAndPush(request, paths, staging.restoreState)
  }

  internal companion object {
    fun readHandoff(envelope: Map<String, Any?>): FeatureTaskRuntimeCommitPushHandoffResult =
      FeatureTaskRuntimeSubtaskFinalisationHandoff.readHandoff(envelope)

    fun withCommitSha(
      envelope: Map<String, Any?>,
      commitSha: String,
    ): Map<String, Any?> = FeatureTaskRuntimeSubtaskFinalisationHandoff.withCommitSha(envelope, commitSha)
  }
}

internal sealed interface FinalisationStagingOutcome

internal data class FinalisationStagingReady(val restoreState: WorkflowGitIndexSnapshot) :
  FinalisationStagingOutcome

internal data class FinalisationStagingBlocked(
  val result: FeatureTaskRuntimeSubtaskFinalisationBlocked,
) : FinalisationStagingOutcome

internal fun FeatureTaskRuntimeSubtaskFinalisation.prepareStaging(stageable: List<String>): FinalisationStagingOutcome {
  if (stageable.isEmpty()) return FinalisationStagingReady(restoreState = WorkflowGitIndexSnapshot.EMPTY)
  val indexSnapshot =
    when (val snapshot = gitOperations.captureIndexState(repoRoot, stageable)) {
      is WorkflowGitIndexSnapshotResult.Captured -> snapshot.snapshot
      is WorkflowGitIndexSnapshotResult.Failed ->
        return FinalisationStagingBlocked(
          blocked("the pre-finalisation index could not be captured (${snapshot.error})"),
        )
    }
  val staged = gitOperations.stagePaths(repoRoot, stageable)
  if (staged !is WorkflowGitOperationResult.Ok) {
    return FinalisationStagingBlocked(
      blocked(restoring(staged.error, stageable, indexSnapshot)),
    )
  }
  return FinalisationStagingReady(restoreState = indexSnapshot)
}

fun FeatureTaskRuntimeSubtaskFinalisation.commitAndPush(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  stageable: List<String>,
  restoreState: WorkflowGitIndexSnapshot,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val branch = request.metadata.branch
  val decision =
    decide(
      branch = branch,
      identity = request.identity,
      durableCommitSha = request.durableCommitSha,
      sequenceNumber = request.sequenceNumber,
    ).let { decision ->
      SupersededCheckpointPromoter(gitOperations).run {
        decision.promote(
          repoRoot = repoRoot,
          branch = branch,
          durableCommitSha = request.durableCommitSha,
          sequenceNumber = request.sequenceNumber,
          stageable = stageable,
        )
      }
    }
  val rewrites = decision is FeatureTaskRuntimeSubtaskCommitAmend
  val message =
    FeatureTaskRuntimeCheckpointMessage.finalise(
      request.handoff.outcomeMessage,
      request.metadata,
      request.identity,
    )
  val commit =
    gitOperations.writeSubtaskCommitPreservingHistory(
      SubtaskCommitPreservationRequest(
        repoRoot = repoRoot,
        decision = decision,
        identity = request.identity,
        message = message,
        allowUnchangedIndex = true,
        record = record,
      ),
    )
  if (commit !is WorkflowGitOperationResult.Ok) return blocked(restoring(commit.error, stageable, restoreState))
  val commitSha =
    commit.value.orEmpty().trim().takeIf(String::isNotBlank)
      ?: return blocked(restoring("the finalisation commit returned an empty sha", stageable, restoreState))
  val recordFailure = recordCommit(commitSha, stageable)
  if (recordFailure != null) return FeatureTaskRuntimeSubtaskFinalisationBlocked(recordFailure)
  return finalizeCommittedSubtask(
    FinalizeCommittedSubtaskInput(
      request = request,
      branch = branch,
      stageable = stageable,
      commitSha = commitSha,
      rewrites = rewrites,
    ),
  )
}

private data class FinalizeCommittedSubtaskInput(
  val request: FeatureTaskRuntimeSubtaskFinaliseRequest,
  val branch: String,
  val stageable: List<String>,
  val commitSha: String,
  val rewrites: Boolean,
)

private fun FeatureTaskRuntimeSubtaskFinalisation.finalizeCommittedSubtask(
  input: FinalizeCommittedSubtaskInput,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val forcedWithLease = input.rewrites && remoteDiverged(input.branch, input.commitSha)
  val pushFailure = push(input.branch, input.request.identity, input.commitSha, forcedWithLease)
  if (pushFailure != null) return blocked(pushFailure)
  if (!input.request.manifestCommitSha.isNullOrBlank()) {
    gitOperations.pruneSubtaskCheckpointRefs(
      repoRoot = repoRoot,
      request =
        FeatureTaskRuntimeCheckpointRefPruneRequest(
          issueKey = input.request.identity.issueKey,
          subtaskId = input.request.identity.subtaskId,
          manifestCommitSha = input.request.manifestCommitSha,
          featureBranch = input.branch,
        ),
      record = record,
    )
  }
  return FeatureTaskRuntimeSubtaskFinalised(
    commitSha = input.commitSha,
    stagedPaths = input.stageable,
    forcedWithLease = forcedWithLease,
  )
}

fun FeatureTaskRuntimeSubtaskFinalisation.restoring(
  error: String,
  paths: List<String>,
  snapshot: WorkflowGitIndexSnapshot,
): String {
  val restored = gitOperations.restoreIndexState(repoRoot, paths, snapshot)
  return if (restored is WorkflowGitOperationResult.Ok) {
    "$error; the pre-finalisation index was restored and the working tree is unchanged"
  } else {
    "$error; the pre-finalisation index could NOT be restored (${restored.error}) — inspect " +
      "`git status` before committing anything yourself"
  }
}

private fun FeatureTaskRuntimeSubtaskFinalisation.publishUnchangedHead(
  request: FeatureTaskRuntimeSubtaskFinaliseRequest,
): FeatureTaskRuntimeSubtaskFinalisationResult {
  val head = gitOperations.headCommitSha(repoRoot)
  val commitSha =
    head.value.orEmpty().trim().takeIf { head is WorkflowGitOperationResult.Ok && it.isNotBlank() }
      ?: return blocked("the finalisation commit returned an empty sha")
  val recordFailure = recordCommit(commitSha, emptyList())
  if (recordFailure != null) return FeatureTaskRuntimeSubtaskFinalisationBlocked(recordFailure)
  return finalizeCommittedSubtask(
    FinalizeCommittedSubtaskInput(
      request = request,
      branch = request.metadata.branch,
      stageable = emptyList(),
      commitSha = commitSha,
      rewrites = false,
    ),
  )
}

fun FeatureTaskRuntimeSubtaskFinalisation.blocked(reason: String) =
  FeatureTaskRuntimeSubtaskFinalisationBlocked(
    "needs_human: subtask finalisation could not complete because $reason.",
  )
