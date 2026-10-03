package skillbill.engine.featuretask.runloop.output

import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.core.CheckpointCommitMessageArgs
import skillbill.engine.featuretask.runloop.core.CommitCheckpointArgs
import skillbill.engine.featuretask.runloop.core.RecordCheckpointIdentityArgs
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult

object FeatureTaskRuntimeRunLoopRepairReceipt {
  internal fun blockRemediationBaseSha(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    reenteredStepId: String,
    error: String,
  ): Boolean {
    context.coupledRunTransitions.transitionCheckpointRemediationBlock(
      context.request,
      precedingPhaseId,
      "Feature-task-runtime could not record the pre-fix remediation base sha before re-entering " +
        reenteredStepId + (if (error.isBlank()) "." else " ($error).") +
        " Without it the reserved remediation pass would silently review the full base-to-current " +
        "delta instead of the remediation delta.",
      context.session.resolvedBranch,
    )
    return false
  }

  private fun blockCheckpointAfterIndexMutation(
    context: PhaseCheckpointRemediationContext,
    args: CommitCheckpointArgs,
    error: String,
    indexSnapshot: WorkflowGitIndexSnapshot,
  ): Boolean =
    with(FeatureTaskRuntimeRunLoopCheckpoint) {
      FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
        context,
        args.precedingPhaseId,
        args.branch,
        FeatureTaskRuntimeRunLoopCheckpoint.withIndexRestoreOutcome(
          context.request,
          context.gitOperations,
          error,
          args.ownedPaths,
          indexSnapshot,
        ),
        args.blockedReason,
      )
    }

  internal fun commitCheckpoint(
    context: PhaseCheckpointRemediationContext,
    args: CommitCheckpointArgs,
  ): Boolean {
    with(context) {
      val indexSnapshot =
        when (val snapshot = gitOperations.captureIndexState(request.repoRoot, args.ownedPaths)) {
          is WorkflowGitIndexSnapshotResult.Captured -> snapshot.snapshot
          is WorkflowGitIndexSnapshotResult.Failed ->
            return with(FeatureTaskRuntimeRunLoopCheckpoint) {
              FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
                context,
                args.precedingPhaseId,
                args.branch,
                snapshot.error,
                args.blockedReason,
              )
            }
        }
      val parentSha =
        gitOperations.headCommitSha(request.repoRoot)
          .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.trim()?.takeIf(String::isNotBlank)
      val attempt = FeatureTaskRuntimeRunLoopRepairReceipt.stageAndWriteCheckpoint(context, args)
      val commitSha =
        attempt.commitSha
          ?: return FeatureTaskRuntimeRunLoopRepairReceipt.blockCheckpointAfterIndexMutation(
            context,
            args,
            attempt.error,
            indexSnapshot,
          )
      return with(FeatureTaskRuntimeRunLoopCheckpoint) {
        FeatureTaskRuntimeRunLoopCheckpoint.recordCheckpointIdentity(
          context,
          RecordCheckpointIdentityArgs(
            precedingPhaseId = args.precedingPhaseId,
            branch = args.branch,
            loopId = args.loopId,
            ownedPaths = args.ownedPaths,
            parentSha = parentSha,
            commitSha = commitSha,
            blockedReason = args.blockedReason,
          ),
        )
      }
    }
  }

  private fun stageAndWriteCheckpoint(
    context: PhaseCheckpointRemediationContext,
    args: CommitCheckpointArgs,
  ): CheckpointCommitAttempt {
    with(context) {
      val staged = gitOperations.stagePaths(request.repoRoot, args.ownedPaths)
      if (staged !is WorkflowGitOperationResult.Ok) {
        return CheckpointCommitAttempt(commitSha = null, error = staged.error)
      }
      val subtaskIdentity = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitIdentity(request)
      val message =
        FeatureTaskRuntimeRunLoopCheckpoint.checkpointCommitMessage(
          request,
          progress,
          diagnostics,
          CheckpointCommitMessageArgs(
            branch = args.branch,
            phaseId = args.precedingPhaseId,
            loopId = args.loopId,
            identity = subtaskIdentity,
            intent = args.intent,
          ),
        )
      val commit =
        with(FeatureTaskRuntimeRunLoopCheckpoint) {
          FeatureTaskRuntimeRunLoopCheckpoint.writeSubtaskCommit(context, args.branch, message, subtaskIdentity)
        }
      if (commit !is WorkflowGitOperationResult.Ok) {
        return CheckpointCommitAttempt(commitSha = null, error = commit.error)
      }
      val commitSha = commit.value.orEmpty().trim()
      return if (commitSha.isBlank()) {
        CheckpointCommitAttempt(commitSha = null, error = "the checkpoint commit returned an empty sha")
      } else {
        CheckpointCommitAttempt(commitSha = commitSha, error = "")
      }
    }
  }
}

private data class CheckpointCommitAttempt(val commitSha: String?, val error: String)
