package skillbill.engine.featuretask.runloop.checkpoint

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.adoptionWarning
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCheckpointDecision
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.runloop.core.CheckpointCommitMessageArgs
import skillbill.engine.featuretask.runloop.core.CommitCheckpointArgs
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.RecordCheckpointIdentityArgs
import skillbill.engine.featuretask.runloop.core.RemediationCheckpointCommit
import skillbill.engine.featuretask.runloop.core.remediationCheckpointBlockedReason
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopRepairReceipt
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal data class RemediationCommitPrepared(
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String,
  val ownedPaths: List<String>,
  val indexSnapshot: WorkflowGitIndexSnapshot,
  val parentSha: String?,
  val subtaskIdentity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val message: String,
)

object FeatureTaskRuntimeRunLoopCheckpointRemediation {
  internal fun recordRemediationBaseSha(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    reenteredStepId: String,
    commitSha: String? = null,
  ): Boolean {
    with(context) {
      if (!isGoalContinuationRun(request)) return true
      if (FeatureTaskRuntimeRunLoopPhaseBlocking.goalReviewStateOrNull(
          request,
          goalContinuationRecorder,
        ) == null
      ) {
        return true
      }
      val baseSha =
        commitSha?.trim()?.takeIf(String::isNotBlank) ?: run {
          val head = gitOperations.headCommitSha(request.repoRoot)
          if (head !is WorkflowGitOperationResult.Ok || head.value.isBlank()) {
            return FeatureTaskRuntimeRunLoopRepairReceipt.blockRemediationBaseSha(
              context,
              precedingPhaseId,
              reenteredStepId,
              head.error.ifBlank { "HEAD resolved to an empty sha." },
            )
          }
          head.value.trim()
        }
      return runCatching {
        goalContinuationRecorder.updateReviewState(
          request.workflowId,
        ) { state ->
          state.copy(remediationBaseSha = baseSha)
        }
      }.fold(
        onSuccess = { recorded ->
          if (recorded != null) {
            true
          } else {
            FeatureTaskRuntimeRunLoopRepairReceipt.blockRemediationBaseSha(
              context,
              precedingPhaseId,
              reenteredStepId,
              "the review persistence.state could not be updated.",
            )
          }
        },
        onFailure = { error ->
          FeatureTaskRuntimeRunLoopRepairReceipt.blockRemediationBaseSha(
            context,
            precedingPhaseId,
            reenteredStepId,
            error.message.orEmpty(),
          )
        },
      )
    }
  }

  internal fun establishRemediationCheckpoint(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    loopId: String,
  ): Boolean {
    with(context) {
      if (FeatureTaskRuntimeRunLoopCheckpointRemediation.remediationCheckpointSkippable(session)) {
        return FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
          context,
          precedingPhaseId,
          loopId,
          commitSha = null,
          parentSha = null,
        )
      }
      val branch = requireNotNull(session.resolvedBranch)
      if (
        FeatureTaskRuntimeRunLoopCheckpointRemediation.remediationCheckpointOffBranch(
          request,
          gitOperations,
          branch,
        )
      ) {
        return FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
          context,
          precedingPhaseId,
          loopId,
          commitSha = null,
          parentSha = null,
        )
      }
      val scope =
        with(FeatureTaskRuntimeRunLoopCheckpoint) {
          FeatureTaskRuntimeRunLoopCheckpoint.resolveCheckpointScope(
            context,
            precedingPhaseId,
            branch,
          ) { errorBranch, error ->
            remediationCheckpointBlockedReason(
              errorBranch,
              error,
            )
          }
        } ?: return false
      return when (scope) {
        is FeatureTaskRuntimeCheckpointDecision.Skip ->
          FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
            context,
            precedingPhaseId,
            loopId,
            commitSha = null,
            parentSha = null,
          )
        is FeatureTaskRuntimeCheckpointDecision.Block -> {
          coupledRunTransitions.transitionCheckpointRemediationBlock(
            request,
            precedingPhaseId,
            scope.reason,
            session.resolvedBranch,
          )
          false
        }
        is FeatureTaskRuntimeCheckpointDecision.Stage ->
          FeatureTaskRuntimeRunLoopCheckpointRemediation.establishRemediationCheckpointStage(
            context,
            precedingPhaseId,
            branch,
            loopId,
            scope,
          )
      }
    }
  }

  internal fun commitRemediationCheckpoint(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    ownedPaths: List<String>,
  ): RemediationCheckpointCommit? {
    with(context) {
      val prepared =
        FeatureTaskRuntimeRunLoopCheckpointRemediation.prepareRemediationCommit(
          context,
          precedingPhaseId,
          branch,
          loopId,
          ownedPaths,
        ) ?: return null
      return FeatureTaskRuntimeRunLoopCheckpointRemediation.finalizeRemediationCommit(context, prepared)
    }
  }

  internal fun finalizeRemediationCommit(
    context: PhaseCheckpointRemediationContext,
    prepared: RemediationCommitPrepared,
  ): RemediationCheckpointCommit? {
    with(context) {
      val commit =
        with(FeatureTaskRuntimeRunLoopCheckpoint) {
          FeatureTaskRuntimeRunLoopCheckpoint.writeSubtaskCommit(
            context,
            prepared.branch,
            prepared.message,
            prepared.subtaskIdentity,
          )
        }
      if (commit !is WorkflowGitOperationResult.Ok) {
        blockRemediationCommitFailure(context, prepared, commit.error)
        return null
      }
      val commitSha = commit.value.orEmpty().trim()
      if (commitSha.isBlank()) {
        blockRemediationCommitFailure(
          context,
          prepared,
          "remediation checkpoint commit returned an empty sha",
        )
        return null
      }
      val recorded =
        with(FeatureTaskRuntimeRunLoopCheckpoint) {
          FeatureTaskRuntimeRunLoopCheckpoint.recordCheckpointIdentity(
            context,
            RecordCheckpointIdentityArgs(
              precedingPhaseId = prepared.precedingPhaseId,
              branch = prepared.branch,
              loopId = prepared.loopId,
              ownedPaths = prepared.ownedPaths,
              parentSha = prepared.parentSha,
              commitSha = commitSha,
              blockedReason = FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
            ),
          )
        }
      if (!recorded) {
        FeatureTaskRuntimeRunLoopCheckpointRemediation.rollbackRemediationCheckpointCommit(
          context,
          commitSha,
          prepared.parentSha,
          identityRecorded = false,
        )
        return null
      }
      return RemediationCheckpointCommit(commitSha = commitSha, parentSha = prepared.parentSha)
    }
  }

  private fun blockRemediationCommitFailure(
    context: PhaseCheckpointRemediationContext,
    prepared: RemediationCommitPrepared,
    error: String,
  ) {
    with(context) {
      with(FeatureTaskRuntimeRunLoopCheckpoint) {
        FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
          context,
          prepared.precedingPhaseId,
          prepared.branch,
          FeatureTaskRuntimeRunLoopCheckpoint.withIndexRestoreOutcome(
            request,
            gitOperations,
            error,
            prepared.ownedPaths,
            prepared.indexSnapshot,
          ),
          FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
        )
      }
    }
  }

  internal fun recordRemediationBaseIfNeeded(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    loopId: String,
    commitSha: String?,
    parentSha: String?,
  ): Boolean {
    with(context) {
      if (loopId != FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID) return true
      val recorded =
        FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseSha(
          context,
          precedingPhaseId,
          transitions.backwardEdges.first { it.loopId == loopId }.destinationPhaseId,
          commitSha,
        )
      if (recorded) return true
      if (commitSha != null) {
        FeatureTaskRuntimeRunLoopCheckpointRemediation.rollbackRemediationCheckpointCommit(
          context,
          commitSha,
          parentSha,
          identityRecorded = true,
        )
      }
      return false
    }
  }

  internal fun rollbackRemediationCheckpointCommit(
    context: PhaseCheckpointRemediationContext,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
  ) {
    with(context) {
      val normalizedCommit = commitSha.trim()
      val head = gitOperations.headCommitSha(request.repoRoot)
      if (head !is WorkflowGitOperationResult.Ok || head.value.trim() != normalizedCommit) return
      val identities =
        with(FeatureTaskRuntimeRunLoopCheckpoint) {
          FeatureTaskRuntimeRunLoopCheckpoint.checkpointIdentitiesForRollback(context, normalizedCommit)
        }
      val restoreSha =
        FeatureTaskRuntimeRunLoopCheckpointRemediation.remediationRollbackTargetSha(
          context,
          identities = identities,
          commitSha = normalizedCommit,
          parentSha = parentSha,
          identityRecorded = identityRecorded,
        ) ?: return
      val reset = gitOperations.resetSoftToCommit(request.repoRoot, restoreSha)
      if (reset !is WorkflowGitOperationResult.Ok) {
        FeatureTaskRuntimeRunLoopCheckpoint.recordRemediationRollbackDegradation(
          context,
          seam = "FeatureTaskRuntimeRunLoop.rollbackRemediationCheckpointCommit",
          valueUsed = restoreSha,
          valueExpected = "successful soft reset to restore target",
          cause = reset.error.ifBlank { "resetSoftToCommit failed" },
        )
      }
    }
  }

  internal fun remediationRollbackTargetSha(
    context: PhaseCheckpointRemediationContext,
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    parentSha: String?,
    identityRecorded: Boolean,
  ): String? {
    with(context) {
      val fallback = parentSha?.trim()?.takeIf(String::isNotBlank)
      val predecessor = rollbackPredecessor(identities, commitSha, identityRecorded) ?: return fallback
      return FeatureTaskRuntimeRunLoopCheckpointRemediation.resolvedPredecessorSha(context, predecessor) ?: fallback
    }
  }

  internal fun rollbackPredecessor(
    identities: List<FeatureTaskRuntimeCheckpointIdentity>,
    commitSha: String,
    identityRecorded: Boolean,
  ): FeatureTaskRuntimeCheckpointIdentity? {
    val currentIdentity = if (identityRecorded) identities.lastOrNull { it.commitSha == commitSha } else null
    return when {
      currentIdentity != null && currentIdentity.sequenceNumber > 0 ->
        identities.find { it.sequenceNumber == currentIdentity.sequenceNumber - 1 }
      currentIdentity != null -> null
      else -> identities.maxByOrNull { it.sequenceNumber }
    }
  }

  internal fun resolvedPredecessorSha(
    context: PhaseCheckpointRemediationContext,
    predecessor: FeatureTaskRuntimeCheckpointIdentity,
  ): String? {
    with(context) {
      val predecessorCommitSha = predecessor.commitSha.trim()
      if (predecessorCommitSha.isBlank()) {
        FeatureTaskRuntimeRunLoopCheckpoint.recordRemediationRollbackDegradation(
          context,
          seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
          valueUsed = "(blank)",
          valueExpected = "resolvable predecessor identity commit",
          cause = "predecessor identity commit sha was missing or blank",
        )
        return null
      }
      val resolved = gitOperations.resolveCommit(request.repoRoot, predecessorCommitSha)
      val predecessorSha =
        resolved.value.orEmpty().trim()
          .takeIf { resolved is WorkflowGitOperationResult.Ok && it.isNotBlank() }
      if (predecessorSha == null) {
        FeatureTaskRuntimeRunLoopCheckpoint.recordRemediationRollbackDegradation(
          context,
          seam = "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha",
          valueUsed = predecessorCommitSha,
          valueExpected = "resolvable predecessor identity commit",
          cause =
            resolved.error.takeIf { resolved !is WorkflowGitOperationResult.Ok && it.isNotBlank() }
              ?: "predecessor commit '$predecessorCommitSha' did not resolve",
        )
      }
      return predecessorSha
    }
  }

  internal fun checkpointEstablished(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    loopId: String?,
    intent: String,
    blockedReason: (
      String,
      String,
    ) -> String,
  ): Boolean {
    with(context) {
      val branch = session.resolvedBranch
      if (branch == null || protectedBranchName(branch) != null) {
        return true
      }
      val head = gitOperations.currentBranch(request.repoRoot)
      if (head !is WorkflowGitOperationResult.Ok || head.value.trim() != branch.trim()) {
        return true
      }
      val scope =
        with(FeatureTaskRuntimeRunLoopCheckpoint) {
          FeatureTaskRuntimeRunLoopCheckpoint.resolveCheckpointScope(context, precedingPhaseId, branch, blockedReason)
        } ?: return false
      return when (scope) {
        is FeatureTaskRuntimeCheckpointDecision.Skip -> true
        is FeatureTaskRuntimeCheckpointDecision.Block -> {
          coupledRunTransitions.transitionCheckpointRemediationBlock(
            request,
            precedingPhaseId,
            scope.reason,
            session.resolvedBranch,
          )
          false
        }
        is FeatureTaskRuntimeCheckpointDecision.Stage -> {
          if (scope.adoptedPaths.isNotEmpty()) {
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              adoptionWarning(branch, scope.adoptedPaths),
            )
          }
          with(FeatureTaskRuntimeRunLoopRepairReceipt) {
            FeatureTaskRuntimeRunLoopRepairReceipt.commitCheckpoint(
              context,
              CommitCheckpointArgs(
                precedingPhaseId = precedingPhaseId,
                branch = branch,
                loopId = loopId,
                intent = intent,
                ownedPaths = scope.ownedPaths,
                blockedReason = blockedReason,
              ),
            )
          }
        }
      }
    }
  }

  internal fun remediationCheckpointSkippable(session: FeatureTaskRuntimeRunSessionObservations): Boolean {
    val branch = session.resolvedBranch
    return branch == null || protectedBranchName(branch) != null
  }

  internal fun remediationCheckpointOffBranch(
    request: FeatureTaskRuntimeRunFacts,
    gitOperations: WorkflowGitOperations,
    branch: String,
  ): Boolean {
    val head = gitOperations.currentBranch(request.repoRoot)
    return head !is WorkflowGitOperationResult.Ok || head.value.trim() != branch.trim()
  }

  internal fun establishRemediationCheckpointStage(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    scope: FeatureTaskRuntimeCheckpointDecision.Stage,
  ): Boolean {
    with(context) {
      if (scope.adoptedPaths.isNotEmpty()) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          adoptionWarning(branch, scope.adoptedPaths),
        )
      }
      val committed =
        FeatureTaskRuntimeRunLoopCheckpointRemediation.commitRemediationCheckpoint(
          context,
          precedingPhaseId = precedingPhaseId,
          branch = branch,
          loopId = loopId,
          ownedPaths = scope.ownedPaths,
        ) ?: return false
      return FeatureTaskRuntimeRunLoopCheckpointRemediation.recordRemediationBaseIfNeeded(
        context,
        precedingPhaseId = precedingPhaseId,
        loopId = loopId,
        commitSha = committed.commitSha,
        parentSha = committed.parentSha,
      )
    }
  }

  internal fun prepareRemediationCommit(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    branch: String,
    loopId: String,
    ownedPaths: List<String>,
  ): RemediationCommitPrepared? {
    with(context) {
      val indexSnapshot =
        when (val snapshot = gitOperations.captureIndexState(request.repoRoot, ownedPaths)) {
          is WorkflowGitIndexSnapshotResult.Captured -> snapshot.snapshot
          is WorkflowGitIndexSnapshotResult.Failed ->
            return blockRemediationCommitPreparation(
              context,
              precedingPhaseId,
              branch,
              snapshot.error,
            )
        }
      val parentSha =
        gitOperations.headCommitSha(request.repoRoot)
          .takeIf { it is WorkflowGitOperationResult.Ok }?.value?.trim()?.takeIf(String::isNotBlank)
      val staged = gitOperations.stagePaths(request.repoRoot, ownedPaths)
      if (staged !is WorkflowGitOperationResult.Ok) {
        return blockRemediationCommitPreparation(
          context,
          precedingPhaseId,
          branch,
          FeatureTaskRuntimeRunLoopCheckpoint.withIndexRestoreOutcome(
            request,
            gitOperations,
            staged.error,
            ownedPaths,
            indexSnapshot,
          ),
        )
      }
      val subtaskIdentity = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitIdentity(request)
      val message =
        FeatureTaskRuntimeRunLoopCheckpoint.checkpointCommitMessage(
          request,
          progress,
          diagnostics,
          CheckpointCommitMessageArgs(
            branch = branch,
            phaseId = precedingPhaseId,
            loopId = loopId,
            identity = subtaskIdentity,
            intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
          ),
        )
      return RemediationCommitPrepared(
        precedingPhaseId = precedingPhaseId,
        branch = branch,
        loopId = loopId,
        ownedPaths = ownedPaths,
        indexSnapshot = indexSnapshot,
        parentSha = parentSha,
        subtaskIdentity = subtaskIdentity,
        message = message,
      )
    }
  }

  private fun blockRemediationCommitPreparation(
    context: PhaseCheckpointRemediationContext,
    precedingPhaseId: String,
    branch: String,
    reason: String,
  ): RemediationCommitPrepared? {
    FeatureTaskRuntimeRunLoopCheckpoint.blockCheckpoint(
      context,
      precedingPhaseId,
      branch,
      reason,
      FeatureTaskRuntimeRunLoopCheckpoint.remediationCheckpointBlockedReasonFor(),
    )
    return null
  }
}

fun FeatureTaskRuntimeResolvedBranch.baselineOwnedPathsForCheckpoint(): List<String> =
  baselineOwnedPaths.ifEmpty { baselineUntrackedPaths }

internal fun PhaseRunRecords.goalStartBaselinePaths(request: FeatureTaskRuntimeRunFacts): List<String>? =
  request.goalContinuation?.parentWorkflowId
    ?.takeIf(String::isNotBlank)
    ?.let(::loadGoalStartResolvedBranch)
    ?.baselineOwnedPathsForCheckpoint()
