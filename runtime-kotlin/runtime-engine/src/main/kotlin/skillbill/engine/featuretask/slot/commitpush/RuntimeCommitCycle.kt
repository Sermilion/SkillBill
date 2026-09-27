package skillbill.engine.featuretask.slot.commitpush

import skillbill.application.decomposition.baseBranch
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMetadata
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskFinalisation
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoff
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushReceipt
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalised
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.core.CommitPushBlocked
import skillbill.engine.featuretask.runloop.core.CommitPushNotApplicable
import skillbill.engine.featuretask.runloop.core.CommitPushSettled
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSubtaskCommit
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.RecordFinalisedCheckpointIdentityArgs
import skillbill.engine.featuretask.runloop.core.SubtaskCommitLedgerState
import skillbill.engine.featuretask.runloop.core.UnownedWorktreeCommitShaArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.validation.ReadinessCommitPushSettleRequest
import skillbill.engine.featuretask.validation.ReadinessCommitPushSettleResult
import skillbill.engine.featuretask.validation.ReadinessCommittedHeadBindRequest
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

private data class FinaliseSubtaskArgs(
  val branch: String,
  val ledger: SubtaskCommitLedgerState,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val subject: String,
)

private data class BindCommittedHeadArgs(
  val run: PhaseRun,
  val iteration: Int,
  val branch: String,
  val baseBranch: String,
  val outcome: FeatureTaskRuntimeSubtaskFinalised,
)

object RuntimeCommitCycle {
  internal fun PhaseAttemptEnvironment.runDeclaredCommitPushCycle(run: PhaseRun): PhaseOutcome {
    val iteration = state.nextIteration(run.phaseId)
    persistRunning(run, iteration)?.let { return it }
    observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
    return settle(run, iteration)
  }

  internal fun runtimeOwnedCommitPushOutput(receipt: FeatureTaskRuntimeCommitPushReceipt): String {
    val result = linkedMapOf<String, Any?>()
    receipt.commitSha?.trim()?.takeIf(String::isNotBlank)?.let { sha ->
      result[DecompositionManifestPayloadKeys.COMMIT_SHA] = sha
    }
    receipt.branch?.trim()?.takeIf(String::isNotBlank)?.let { branch ->
      result[DecompositionPlanningPayloadKeys.BRANCH] = branch
    }
    receipt.baseBranch?.trim()?.takeIf(String::isNotBlank)?.let { baseBranch ->
      result[DecompositionPlanningPayloadKeys.BASE_BRANCH] = baseBranch
    }
    result[FeatureTaskRuntimeCommitPushPayloadKeys.PUSHED] = receipt.pushed
    return JsonCodec.mapToJsonString(
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        SharedPayloadKeys.STATUS to STATUS_COMPLETED,
        SharedPayloadKeys.SUMMARY to "Runtime staged every dirty path, committed, and recorded commit_sha.",
        SharedPayloadKeys.PRODUCED_OUTPUTS to
          mapOf(
            FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT to result,
          ),
      ),
    )
  }

  private fun PhaseAttemptEnvironment.settle(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome {
    val branch =
      FeatureTaskRuntimeRunLoopSubtaskCommit.finalisationBranch(request, session, phaseGates)
        ?: return settleUnownedHead(run, iteration)
    val baseBranch = recorder.loadResolvedBranch(request.workflowId)?.baseBranch ?: "main"
    val readiness = commitPushReadiness(this, run.phaseId, baseBranch)
    if (readiness is ReadinessCommitPushSettleResult.Blocked) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase(
        request,
        state,
        recorder,
        goalContinuationRecorder,
        phaseBlockArgs(run, iteration, readiness.reason, observability)
          .copy(failureDisposition = readiness.failureDisposition),
      )
    }
    return finaliseAndBindCommitPush(this, run, iteration, branch, baseBranch)
  }

  private fun commitPushReadiness(
    context: PhaseAttemptEnvironment,
    stepId: String,
    baseBranch: String,
  ): ReadinessCommitPushSettleResult {
    val changedPaths =
      FeatureTaskRuntimeRunLoopSubtaskCommit.commitPushChangedPaths(
        context.request,
        context.phaseGates,
        baseBranch,
      )
    return context.phaseGates.readinessGateCoordinator.settleBeforeCommitPush(
      ReadinessCommitPushSettleRequest(
        workflowId = context.request.workflowId,
        stepId = stepId,
        repoRoot = context.request.repoRoot,
        baseBranch = baseBranch,
        changedPaths = changedPaths.paths,
        changedPathsError = changedPaths.error,
        gitOperations = context.phaseGates.gitOperations,
      ),
    )
  }

  private fun finaliseAndBindCommitPush(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    iteration: Int,
    branch: String,
    baseBranch: String,
  ): PhaseOutcome {
    val identity = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitIdentity(context.request)
    val ledger =
      FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitLedgerState(
        context.request,
        context.recorder,
        context.diagnostics,
        identity,
      )
    val outcome =
      finaliseSubtask(
        context,
        run,
        FinaliseSubtaskArgs(branch, ledger, identity, context.commitSubject(identity.subtaskId)),
      )
    return when (outcome) {
      is FeatureTaskRuntimeSubtaskFinalisationBlocked -> context.block(run, iteration, outcome.reason)
      is FeatureTaskRuntimeSubtaskFinalised ->
        bindCommittedHead(
          context,
          BindCommittedHeadArgs(run, iteration, branch, baseBranch, outcome),
        )
    }
  }

  private fun bindCommittedHead(
    context: PhaseAttemptEnvironment,
    args: BindCommittedHeadArgs,
  ): PhaseOutcome {
    val rebound =
      context.phaseGates.readinessGateCoordinator.bindCommittedHead(
        ReadinessCommittedHeadBindRequest(
          workflowId = context.request.workflowId,
          stepId = args.run.phaseId,
          repoRoot = context.request.repoRoot,
          baseBranch = args.baseBranch,
          gitOperations = context.phaseGates.gitOperations,
          commitSha = args.outcome.commitSha,
        ),
      )
    return if (rebound is ReadinessCommitPushSettleResult.Blocked) {
      context.block(args.run, args.iteration, rebound.reason)
    } else {
      context.complete(
        args.run,
        args.iteration,
        runtimeOwnedCommitPushOutput(
          FeatureTaskRuntimeCommitPushReceipt(
            commitSha = args.outcome.commitSha,
            branch = args.branch,
            baseBranch = args.baseBranch,
            pushed = true,
          ),
        ),
      )
    }
  }

  private fun finaliseSubtask(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    args: FinaliseSubtaskArgs,
  ) = FeatureTaskRuntimeSubtaskFinalisation(
    gitOperations = context.phaseGates.gitOperations,
    repoRoot = context.request.repoRoot,
    record = { record -> RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, record) },
    recordCommit = { commitSha, stagedPaths ->
      FeatureTaskRuntimeRunLoopSubtaskCommit.recordFinalisedCheckpointIdentity(
        context.request,
        context.state,
        context.recorder,
        context.diagnostics,
        RecordFinalisedCheckpointIdentityArgs(
          run.phaseId,
          args.branch,
          args.ledger,
          commitSha,
          stagedPaths,
        ),
      )
    },
  ).finalise(
    FeatureTaskRuntimeSubtaskFinaliseRequest(
      identity = args.identity,
      durableCommitSha = args.ledger.commitSha,
      sequenceNumber = args.ledger.nextSequenceNumber,
      handoff = FeatureTaskRuntimeCommitPushHandoff(outcomeMessage = args.subject, changedPaths = emptyList()),
      metadata =
        FeatureTaskRuntimeCheckpointMetadata(
          phaseId = run.phaseId,
          loopId = null,
          generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(context.state, null),
          branch = args.branch,
          intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
        ),
    ),
  )

  private fun PhaseAttemptEnvironment.commitSubject(subtaskId: String): String {
    val subtaskName = request.goalContinuation?.subtaskName?.trim()?.takeIf(String::isNotBlank)
    if (subtaskName == null && request.goalContinuation != null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        FeatureTaskRuntimeCheckpointMessage.missingSubtaskNameRecord(request.issueKey, subtaskId),
      )
    }
    return FeatureTaskRuntimeCheckpointMessage.subject(request.issueKey, subtaskName, subtaskId)
  }

  private fun PhaseAttemptEnvironment.settleUnownedHead(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome {
    val accepted =
      accept(
        run,
        runtimeOwnedCommitPushOutput(FeatureTaskRuntimeCommitPushReceipt(commitSha = null)),
      ).getOrElse { error ->
        return block(
          run,
          iteration,
          "Runtime-owned commit_push settlement did not validate: ${error.message.orEmpty()}",
        )
      }
    return when (
      val unowned =
        FeatureTaskRuntimeRunLoopSubtaskCommit.unownedWorktreeCommitSha(
          UnownedWorktreeCommitShaArgs(
            request,
            outputValidator,
            diagnostics,
            phaseGates,
            run,
            accepted.normalizedOutput,
          ),
        )
    ) {
      is CommitPushSettled -> complete(run, iteration, unowned.output.canonicalJson)
      is CommitPushBlocked -> block(run, iteration, unowned.reason)
      CommitPushNotApplicable ->
        block(run, iteration, "commit_push could not resolve a branch or measure HEAD for commit_sha.")
    }
  }

  private fun PhaseAttemptEnvironment.complete(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): PhaseOutcome {
    val accepted =
      accept(run, outputText).getOrElse { error ->
        return block(
          run,
          iteration,
          "Runtime-owned commit_push settlement did not validate: ${error.message.orEmpty()}",
        )
      }
    val normalizedOutput = accepted.normalizedOutput
    if (!persistCompleted(run, iteration, outputText, accepted)) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        request,
        state,
        recorder,
        observability,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned commit_push settlement could not be persisted.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        accepted.repairEvidence,
      ),
    )
  }

  private fun PhaseAttemptEnvironment.persistRunning(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome? {
    val runningPhaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        request,
        state,
        goalContinuationRecorder,
        PhaseStateRequestArgs(
          write =
            PhaseStateWriteArgs(
              run = run,
              iteration = iteration,
              status = STATUS_RUNNING,
              finished = false,
              outputArtifact = null,
            ),
        ),
      )
    state.reserveReviewPass(runningPhaseState.reviewPassNumber)
    if (!recorder.recordPhaseState(runningPhaseState)) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        request,
        state,
        recorder,
        observability,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Commit-push cycle could not persist running phase before finalisation.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    return null
  }

  private fun PhaseAttemptEnvironment.persistCompleted(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
  ): Boolean =
    recorder.recordCompletedPhase(
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        request,
        state,
        goalContinuationRecorder,
        PhaseStateRequestArgs(
          write =
            PhaseStateWriteArgs(
              run = run,
              iteration = iteration,
              status = STATUS_COMPLETED,
              finished = true,
              outputArtifact = outputText,
            ),
          extras =
            PhaseStateRequestAttachments(
              normalizedOutput = acceptedOutput.normalizedOutput,
              repairEvidence = acceptedOutput.repairEvidence,
            ),
        ),
      ),
    )

  private fun PhaseAttemptEnvironment.accept(
    run: PhaseRun,
    outputText: String,
  ): Result<AcceptedFeatureTaskRuntimePhaseOutput> =
    runCatching {
      outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
    }

  private fun PhaseAttemptEnvironment.block(
    run: PhaseRun,
    iteration: Int,
    reason: String,
  ): PhaseOutcome =
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase(
      request,
      state,
      recorder,
      goalContinuationRecorder,
      phaseBlockArgs(run, iteration, reason, observability),
    )
}
