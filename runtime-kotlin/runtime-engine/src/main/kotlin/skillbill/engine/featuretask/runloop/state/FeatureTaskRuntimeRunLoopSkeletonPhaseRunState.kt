package skillbill.engine.featuretask.runloop.state

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.lifecycle.continuation.lastGoalReviewResult
import skillbill.engine.featuretask.lifecycle.continuation.reviewState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.core.BlockAndPersistArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch
import skillbill.engine.featuretask.runloop.core.PersistPhaseArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseReviewPersistenceArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputPersistence
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopReviewCompletion
import skillbill.engine.featuretask.runloop.output.ReviewOutputPersistenceContext
import skillbill.engine.featuretask.runloop.output.isGoalReviewRun
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.attempt.PhaseLaunchPreparation
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.ReviewPassResolution
import skillbill.workflow.model.goalreview.upsertRepairReceipt
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class FeatureTaskRuntimeRunLoopSkeletonPhaseRunState(
  private val context: FeatureTaskRuntimeRunLoopContext,
  private val run: PhaseRun,
) : PhaseRunState {
  private val workflowId = context.request.workflowId
  private val repoRoot = context.request.repoRoot

  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget =
    FeatureTaskRuntimePhaseSettlementTarget(workflowId, attempt)

  override fun launchObservation(stepName: String): PhaseLaunchObservation =
    PhaseLaunchObservation(
      activityStampSink =
        context.activityStampWriter.sink(
          workflowId = workflowId,
          parentWorkflowId = context.request.goalContinuation?.parentWorkflowId,
        ),
      worktreeEditObserver =
        context.worktreeEditJournalWriter.observer(
          repoRoot = repoRoot,
          resolveWorkflowId = { workflowId },
          resolvePhaseId = { stepName },
        ),
    )

  override fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  ) {
    context.state.recordPhaseTokenUsage(stepName, inputTokens, outputTokens)
  }

  override fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead =
    try {
      context.phaseSettlementService.findEnvelope(target.workflowId, stepName, target.attempt)
        ?.let { PhaseSettledEnvelopeRead.Found(it.envelope) }
        ?: PhaseSettledEnvelopeRead.None
    } catch (error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) {
      PhaseSettledEnvelopeRead.Failed(error)
    }

  override fun nextStepIteration(): Int = context.state.nextIteration(run.phaseId)

  override fun reserveReviewPass(): GoalSubtaskReviewPassReservation =
    context.goalContinuationRecorder.reserveGoalReviewPass(workflowId)

  override fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = context.recorder.loadResolvedBranch(workflowId)

  override fun prepareGoalReviewInput(
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation =
    context.goalContinuationRecorder.buildGoalReviewInput(
      workflowId = workflowId,
      gitOperations = context.phaseGates.gitOperations,
      repoRoot = repoRoot,
      scope = FeatureTaskRuntimeGoalContinuationRecorder.GoalReviewInputScope(scopedUntrackedExclusions, ownedPathspec),
    )

  override fun carriedForwardReviewResult(): String? = context.goalContinuationRecorder.lastGoalReviewResult(workflowId)

  override fun completeCarriedForwardReview(
    iteration: Int,
    output: AcceptedFeatureTaskRuntimePhaseOutput,
  ): String? {
    val normalizedOutput = output.normalizedOutput
    val phaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        context.request,
        context.state,
        context.goalContinuationRecorder,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(run, iteration, STATUS_COMPLETED, true, normalizedOutput.canonicalJson),
          extras =
            PhaseStateRequestAttachments(normalizedOutput = normalizedOutput, repairEvidence = output.repairEvidence),
        ),
      )
    context.state.reserveReviewPass(phaseState.reviewPassNumber)
    val prefix = "Carried-forward goal review could not atomically persist its canonical result."
    return runCatching { context.recorder.recordCompletedPhase(phaseState) }.fold(
      onSuccess = { persisted -> if (persisted) null else prefix },
      onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
    )
  }

  override fun reviewPassNumber(): Int =
    FeatureTaskRuntimeRunLoopPhaseBlocking.reviewPassNumber(
      context.request,
      context.goalContinuationRecorder,
      run,
      context.state,
    ) ?: 1

  override fun recordedReviewRunId(passNumber: Int): String? =
    context.state.recordFor(run.phaseId)
      ?.takeIf { (it.reviewPassNumber ?: 1) == passNumber }
      ?.reviewRunId
      ?.takeIf(String::isNotBlank)

  override fun startReview(
    iteration: Int,
    reviewRunId: String,
  ) {
    FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
      context.request,
      context.state,
      context.recorder,
      context.goalContinuationRecorder,
      PersistPhaseArgs(
        write = PhaseStateWriteArgs(run, iteration, STATUS_RUNNING, false, null),
        reviewRunId = reviewRunId,
      ),
    )
  }

  override fun prepareReviewBriefing(
    prompt: PhaseStepPromptSource,
    input: GoalSubtaskReviewInput,
  ) {
    PhaseLaunchPreparation.prepareLaunchForCapture(
      context,
      run.copy(goalReviewInput = input),
      context.state,
      null,
      null,
      prompt,
    )
  }

  override fun reviewLaunched(iteration: Int) {
    context.observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
  }

  override fun recordReviewContentIdentities() {
    FeatureTaskRuntimeRunLoopLaunch.capturePhaseContentIdentities(
      context.request,
      context.session,
      context.phaseGates,
      run.phaseId,
    )
  }

  override fun unaddressedReviewFindings(): List<UnaddressedFinding> =
    context.recorder.fetchUnaddressedLedger(workflowId)

  override fun recordedFindingVerdicts(envelope: Map<String, Any?>): List<ReviewFindingVerdict> =
    context.recorder.recordedFindingVerdicts(envelope)

  override fun completedStepEnvelope(stepId: String): FeatureTaskRuntimeWorkflowArtifactMap? =
    context.state.outputFor(stepId)?.normalizedOutput?.envelopeWireMap()

  override fun completedStepPayload(stepId: String): String? = context.state.outputFor(stepId)?.payload

  override fun resolvedBranchName(): String? = context.session.resolvedBranch

  override fun completedReviewPassCount(): Int? =
    context.goalContinuationRecorder.reviewState(workflowId)?.completedPassCount

  override fun findingVerificationCheckpoint(): List<FeatureTaskRuntimeFindingVerificationDisposition>? =
    context.recorder.loadFindingVerificationCheckpoint(workflowId)

  override fun persistFindingVerificationCheckpoint(
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean = context.recorder.persistFindingVerificationCheckpoint(workflowId, dispositions)

  override fun verificationBoundarySelection() = context.recorder.loadFindingVerificationBoundarySelection(workflowId)

  override fun persistVerificationBoundarySelection(
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean = context.recorder.persistFindingVerificationBoundarySelection(workflowId, selections)

  override fun appendRejectedVerificationFindings(
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  ) {
    context.recorder.appendRejectedVerificationFindings(workflowId, passNumber, rejected)
  }

  override fun persistResolvedReviewTier(resolution: ReviewPassResolution) {
    FeatureTaskRuntimeRunLoopPhaseBlocking.persistResolvedReviewTier(
      context.request,
      context.goalContinuationRecorder,
      run,
      resolution,
    )
  }

  override fun goalReviewState(): GoalSubtaskReviewState? =
    FeatureTaskRuntimeRunLoopPhaseBlocking.goalReviewStateOrNull(context.request, context.goalContinuationRecorder)

  override fun recordRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): Boolean =
    context.goalContinuationRecorder.updateReviewState(workflowId) { it.upsertRepairReceipt(receipt) } != null

  override fun amendReviewRemediationCheckpoint(): Boolean =
    FeatureTaskRuntimeRunLoopCheckpointRemediation.checkpointEstablished(
      context,
      precedingPhaseId = run.phaseId,
      loopId = null,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
      blockedReason = { branch, error ->
        "Feature-task-runtime could not amend review changes on the feature branch '$branch'" +
          (if (error.isBlank()) "." else " ($error).") +
          " Refusing to complete review with uncommitted review fixes."
      },
    )

  override fun retainReviewOutput(
    iteration: Int,
    outputText: String,
  ) {
    val outputBytes = outputText.encodeToByteArray()
    context.recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = workflowId,
        phaseId = run.phaseId,
        attempt = iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = context.clock.instant(),
        byteSize = outputBytes.size.toLong(),
        sha256 = RejectedOutputDiagnosticService.sha256(outputBytes),
        payload = outputBytes,
        generation = context.state.evidenceGeneration(run.policy.generationScoped),
      ),
    )
  }

  override fun completeReview(
    iteration: Int,
    outputText: String,
    output: AcceptedFeatureTaskRuntimePhaseOutput,
    fileManifest: PhaseStepFileManifest,
  ): String? {
    val persistence =
      ReviewOutputPersistenceContext(
        request = context.request,
        state = context.state,
        recorder = context.recorder,
        observability = context.observability,
        goalContinuationRecorder = context.goalContinuationRecorder,
      )
    val args = PhaseReviewPersistenceArgs(run, iteration, context.observability, fileManifest.toPhaseManifest())
    val blocked =
      with(FeatureTaskRuntimeRunLoopReviewCompletion) {
        if (isGoalReviewRun(run)) {
          persistence.persistGoalReviewCompletion(args, output.normalizedOutput, output.repairEvidence)
        } else {
          persistence.persistStandaloneReviewCompletion(args, outputText, output)
        }
      }
    return blocked?.blockedReason
  }

  override fun stepCompleted(iteration: Int) {
    context.observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  }

  override fun blockReviewPreparation(
    attemptCount: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    carriedOutput: AcceptedFeatureTaskRuntimePhaseOutput?,
  ) {
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
      context.request,
      context.state,
      context.recorder,
      context.goalContinuationRecorder.takeIf { isGoalReviewRun(run) },
      BlockAndPersistArgs(
        run = run,
        attemptCount = attemptCount,
        reason = reason,
        observability = context.observability,
        loopId = null,
        edgeIteration = null,
        failureDisposition = disposition,
        payload =
          carriedOutput?.let {
            BlockAndPersistPayload(
              normalizedOutput = it.normalizedOutput,
              outputArtifact = it.normalizedOutput.canonicalJson,
              repairEvidence = it.repairEvidence,
            )
          } ?: BlockAndPersistPayload(),
      ),
    )
  }

  override fun blockReviewStep(
    iteration: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    fileManifest: PhaseStepFileManifest?,
  ) {
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      context.request,
      context.state,
      context.recorder,
      context.observability,
      PhaseBlockRequest(
        run = run,
        attemptCount = iteration,
        reason = reason,
        observability = context.observability,
        payload = BlockAndPersistPayload(fileManifest = fileManifest?.toPhaseManifest()),
        failureDisposition = disposition,
      ),
    )
  }

  override fun isStepCompleted(stepId: String): Boolean = context.state.isComplete(stepId)

  override fun isEvidenceInvalidated(stepId: String): Boolean =
    stepId in context.state.phasesRequiringDurableGateInvalidation()

  override fun reviewedCheckpointFingerprint(): String? =
    FeatureTaskRuntimeRunLoopPhaseBlocking.reviewedCheckpointFingerprint(context.request, context.recorder)

  override fun persistReviewGenerationInvalidation(): Int? =
    context.recorder.persistReviewGenerationInvalidation(workflowId)

  override fun advanceReviewGeneration(
    generation: Int,
    reentryLoopId: String,
  ) {
    context.state.advanceReviewGeneration(generation)
    context.state.resetInvalidatedReviewGeneration()
    if (context.session.pendingReentry?.loopId == reentryLoopId) {
      context.session.transitionReentryPair(null, null)
    }
  }

  override fun completeReservedReviewPass(
    output: String,
    envelope: Map<String, Any?>,
  ): Boolean {
    val recordedVerdicts = context.recorder.recordedFindingVerdicts(envelope)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(envelope, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(envelope, findings)
    return context.goalContinuationRecorder.completeGoalReviewPass(
      request =
        GoalReviewPassCompletionRequest(
          workflowId = workflowId,
          verdict = outcome.verdict,
          unresolvedFindingCount = outcome.unresolvedFindingCount,
          findings = findings,
          rawReviewResult = output,
          normalizedOutput = envelope,
          blockerDispositions =
            GoalSubtaskReviewSummaryReducer.blockerDispositions(
              envelope,
              FeatureTaskRuntimeRunLoopPhaseBlocking.priorBlockerFindingIds(
                context.request,
                context.goalContinuationRecorder,
              ),
            ),
          commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(envelope),
        ),
    ) != null
  }

  override fun settleCarriedForwardReview(output: AcceptedFeatureTaskRuntimePhaseOutput) {
    val phaseId = run.phaseId
    if (context.state.isComplete(phaseId)) {
      return
    }
    val reentry = context.session.activeReentry
    val normalizedOutput = output.normalizedOutput
    val iteration = context.state.nextIteration(phaseId)
    val priorRecord = context.state.recordFor(phaseId)
    val persisted =
      context.recorder.recordCompletedPhase(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = phaseId,
          status = STATUS_COMPLETED,
          attemptCount = iteration,
          resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
          finished = true,
          outputArtifact = normalizedOutput.canonicalJson,
          normalizedOutput = normalizedOutput,
          repairEvidence = output.repairEvidence,
          loopId = reentry?.loopId,
          edgeIteration = reentry?.edgeIteration,
        ),
      )
    if (!persisted) {
      error("Carried-forward goal review could not atomically persist its canonical result.")
    }
    if (reentry != null) context.session.transitionPendingReentry(null)
    context.state.recordCompleted(
      FeatureTaskRuntimePhaseOutput(
        phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        output.repairEvidence,
      ),
    )
  }

  override fun loadGateProgress(): FeatureTaskRuntimeValidationGateProgress? =
    if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
      context.recorder.loadBuildGateProgress(workflowId)
    } else {
      context.recorder.loadValidationGateProgress(workflowId)
    }

  override fun persistGateProgress(progress: FeatureTaskRuntimeValidationGateProgress) {
    if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
      context.recorder.persistBuildGateProgress(workflowId, progress)
    } else {
      context.recorder.persistValidationGateProgress(workflowId, progress)
    }
  }

  private fun PhaseStepFileManifest.toPhaseManifest() = FeatureTaskRuntimePhaseFileManifest(before, after)
}
