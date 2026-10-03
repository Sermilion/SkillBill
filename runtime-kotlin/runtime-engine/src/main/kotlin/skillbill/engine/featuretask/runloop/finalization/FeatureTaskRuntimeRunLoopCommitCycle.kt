package skillbill.engine.featuretask.runloop.finalization

import skillbill.application.decomposition.baseBranch
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeCommitPushPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.requirePublishableBranch
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
import skillbill.engine.featuretask.runloop.core.RecordFinalisedCheckpointIdentityArgs
import skillbill.engine.featuretask.runloop.core.SubtaskCommitLedgerState
import skillbill.engine.featuretask.runloop.core.UnownedWorktreeCommitShaArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.attempt.PhaseRuntimeFinalizationContext
import skillbill.engine.featuretask.slot.attempt.blockAndPersistInPhase
import skillbill.engine.featuretask.slot.attempt.finalizationCoupledProgress
import skillbill.engine.featuretask.slot.attempt.persistFinalizationCompleted
import skillbill.engine.featuretask.slot.attempt.persistFinalizationRequiredRunning
import skillbill.engine.featuretask.validation.ReadinessCommitPushSettleRequest
import skillbill.engine.featuretask.validation.ReadinessCommitPushSettleResult
import skillbill.engine.featuretask.validation.ReadinessCommittedHeadBindRequest
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind

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

internal object FeatureTaskRuntimeRunLoopCommitCycle {
  internal fun PhaseRuntimeFinalizationContext.runDeclaredCommitPushCycle(run: PhaseRun): PhaseOutcome {
    val iteration = progress.phase(run.phaseId).nextIteration
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

  internal fun runtimeOwnedCommitPushOutput(
    phaseId: String,
    receipt: FeatureTaskRuntimeCommitPushReceipt,
  ): String {
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
    val value =
      "Runtime committed " +
        (result[DecompositionManifestPayloadKeys.COMMIT_SHA]?.let { "commit $it" } ?: "no recorded commit") +
        (result[DecompositionPlanningPayloadKeys.BRANCH]?.let { " on branch $it" } ?: "") +
        (result[DecompositionPlanningPayloadKeys.BASE_BRANCH]?.let { " against base $it" } ?: "") +
        if (receipt.pushed) " and pushed it." else " without pushing."
    return JsonCodec.mapToJsonString(
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to phaseId,
        SharedPayloadKeys.STATUS to STATUS_COMPLETED,
        SharedPayloadKeys.SUMMARY to "Runtime staged every dirty path, committed, and recorded commit_sha.",
        SharedPayloadKeys.PRODUCED_OUTPUTS to
          mapOf(
            SharedPayloadKeys.VALUE to value,
            FeatureTaskRuntimeCommitPushPayloadKeys.COMMIT_PUSH_RESULT to result,
          ),
      ),
    )
  }

  private fun PhaseRuntimeFinalizationContext.settle(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome {
    if (request.skeletonDefinition?.runStateKind == SkeletonRunStateKind.IN_MEMORY) {
      return settleInMemory(run, iteration)
    }
    val branch =
      FeatureTaskRuntimeRunLoopSubtaskCommit.finalisationBranch(
        request,
        session,
        gitOperations,
      )
        ?: return settleUnownedHead(run, iteration)
    val baseBranch = recorder.loadResolvedBranch(request.workflowId)?.baseBranch ?: "main"
    val readiness = commitPushReadiness(this, run.phaseId, baseBranch)
    if (readiness is ReadinessCommitPushSettleResult.Blocked) {
      return blockAndPersistInPhase(
        phaseBlockArgs(run, iteration, readiness.reason, observability)
          .copy(failureDisposition = readiness.failureDisposition),
      )
    }
    return finaliseAndBindCommitPush(this, run, iteration, branch, baseBranch)
  }

  private fun PhaseRuntimeFinalizationContext.settleInMemory(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome {
    val resolved = recorder.loadResolvedBranch(request.workflowId)
    val baseBranch = resolved?.baseBranch ?: "main"
    val branch = requirePublishableBranch(resolved?.branch, baseBranch)
    val result = InMemoryCommitPush(gitOperations, request.repoRoot).run(branch, request.issueKey)
    if (result !is WorkflowGitOperationResult.Ok) return block(run, iteration, result.error)
    return complete(
      run,
      iteration,
      runtimeOwnedCommitPushOutput(
        run.phaseId,
        FeatureTaskRuntimeCommitPushReceipt(result.value, branch, baseBranch, pushed = true),
      ),
    )
  }

  private fun commitPushReadiness(
    context: PhaseRuntimeFinalizationContext,
    stepId: String,
    baseBranch: String,
  ): ReadinessCommitPushSettleResult {
    val changedPaths =
      FeatureTaskRuntimeRunLoopSubtaskCommit.commitPushChangedPaths(
        context.request,
        context.gitOperations,
        baseBranch,
      )
    return context.readinessGateCoordinator.settleBeforeCommitPush(
      ReadinessCommitPushSettleRequest(
        workflowId = context.request.workflowId,
        stepId = stepId,
        repoRoot = context.request.repoRoot,
        baseBranch = baseBranch,
        changedPaths = changedPaths.paths,
        changedPathsError = changedPaths.error,
        gitOperations = context.gitOperations,
      ),
    )
  }

  private fun finaliseAndBindCommitPush(
    context: PhaseRuntimeFinalizationContext,
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
    context: PhaseRuntimeFinalizationContext,
    args: BindCommittedHeadArgs,
  ): PhaseOutcome {
    val rebound =
      context.readinessGateCoordinator.bindCommittedHead(
        ReadinessCommittedHeadBindRequest(
          workflowId = context.request.workflowId,
          stepId = args.run.phaseId,
          repoRoot = context.request.repoRoot,
          baseBranch = args.baseBranch,
          gitOperations = context.gitOperations,
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
          args.run.phaseId,
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
    context: PhaseRuntimeFinalizationContext,
    run: PhaseRun,
    args: FinaliseSubtaskArgs,
  ) = FeatureTaskRuntimeSubtaskFinalisation(
    gitOperations = context.gitOperations,
    repoRoot = context.request.repoRoot,
    record = { record -> RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, record) },
    recordCommit = { commitSha, stagedPaths ->
      FeatureTaskRuntimeRunLoopSubtaskCommit.recordFinalisedCheckpointIdentity(
        context.request,
        context.progress,
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
          generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(context.progress, null),
          branch = args.branch,
          intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
        ),
    ),
  )

  private fun PhaseRuntimeFinalizationContext.commitSubject(subtaskId: String): String {
    val subtaskName =
      request.goalContinuation
        ?.subtaskName
        ?.trim()
        ?.takeIf(String::isNotBlank)
    if (subtaskName == null && request.goalContinuation != null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        FeatureTaskRuntimeCheckpointMessage.missingSubtaskNameRecord(request.issueKey, subtaskId),
      )
    }
    return FeatureTaskRuntimeCheckpointMessage.subject(request.issueKey, subtaskName, subtaskId)
  }

  private fun PhaseRuntimeFinalizationContext.settleUnownedHead(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome {
    val accepted =
      accept(
        run,
        runtimeOwnedCommitPushOutput(run.phaseId, FeatureTaskRuntimeCommitPushReceipt(commitSha = null)),
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
            diagnostics,
            gitOperations,
            run,
            accepted,
          ),
        )
    ) {
      is CommitPushSettled -> complete(run, iteration, unowned.output.canonicalJson)
      is CommitPushBlocked -> block(run, iteration, unowned.reason)
      CommitPushNotApplicable ->
        block(run, iteration, "commit_push could not resolve a branch or measure HEAD for commit_sha.")
    }
  }

  private fun PhaseRuntimeFinalizationContext.complete(
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
    val normalizedOutput = accepted
    if (!persistCompleted(run, iteration, outputText, accepted)) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        finalizationCoupledProgress(),
        coupledRunTransitions,
        recorder,
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
        null,
      ),
    )
  }

  private fun PhaseRuntimeFinalizationContext.persistRunning(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome? = persistFinalizationRequiredRunning(run, iteration)

  private fun PhaseRuntimeFinalizationContext.persistCompleted(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    acceptedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ): Boolean = persistFinalizationCompleted(run, iteration, outputText, acceptedOutput)

  private fun accept(
    run: PhaseRun,
    outputText: String,
  ): Result<NormalizedFeatureTaskRuntimePhaseOutput> =
    runCatching {
      NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(outputText, run.phaseId)
    }

  private fun PhaseRuntimeFinalizationContext.block(
    run: PhaseRun,
    iteration: Int,
    reason: String,
  ): PhaseOutcome =
    blockAndPersistInPhase(
      phaseBlockArgs(run, iteration, reason, observability),
    )
}
