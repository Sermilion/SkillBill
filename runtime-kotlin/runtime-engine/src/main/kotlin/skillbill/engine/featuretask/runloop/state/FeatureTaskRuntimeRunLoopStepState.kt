package skillbill.engine.featuretask.runloop.state

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.core.BlockAndPersistArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
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
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.attempt.PhaseLaunchPreparation
import skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.model.goalreview.ReviewPassResolution
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal class FeatureTaskRuntimeRunLoopStepState(
  private val environment: PhaseAttemptEnvironment,
  private val run: PhaseRun,
) : PhaseStepState,
  PhaseRunState by environment.runState,
  PhaseFindingVerificationState by FeatureTaskRuntimeRunLoopFindingVerificationState(environment) {
  private val workflowId = environment.request.workflowId
  private val repoRoot = environment.request.repoRoot

  override fun nextStepIteration(): Int = environment.state.nextIteration(run.phaseId)

  override fun reserveReviewPass(): GoalSubtaskReviewPassReservation =
    environment.goalContinuationRecorder.reserveGoalReviewPass(workflowId)

  override fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = environment.recorder.loadResolvedBranch(workflowId)

  override fun prepareGoalReviewInput(
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation =
    environment.goalContinuationRecorder.buildGoalReviewInput(
      workflowId = workflowId,
      gitOperations = environment.phaseGates.gitOperations,
      repoRoot = repoRoot,
      scopedUntrackedExclusions = scopedUntrackedExclusions,
      ownedPathspec = ownedPathspec,
    )

  override fun carriedForwardReviewResult(): String? =
    environment.goalContinuationRecorder.lastGoalReviewResult(workflowId)

  override fun completeCarriedForwardReview(
    iteration: Int,
    output: AcceptedFeatureTaskRuntimePhaseOutput,
  ): String? {
    val normalizedOutput = output.normalizedOutput
    val phaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        environment.request,
        environment.state,
        environment.goalContinuationRecorder,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(run, iteration, STATUS_COMPLETED, true, normalizedOutput.canonicalJson),
          extras =
            PhaseStateRequestAttachments(normalizedOutput = normalizedOutput, repairEvidence = output.repairEvidence),
        ),
      )
    environment.state.reserveReviewPass(phaseState.reviewPassNumber)
    val prefix = "Carried-forward goal review could not atomically persist its canonical result."
    return runCatching { environment.recorder.recordCompletedPhase(phaseState) }.fold(
      onSuccess = { persisted -> if (persisted) null else prefix },
      onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
    )
  }

  override fun reviewPassNumber(): Int =
    FeatureTaskRuntimeRunLoopPhaseBlocking.reviewPassNumber(
      environment.request,
      environment.goalContinuationRecorder,
      run,
      environment.state,
    ) ?: 1

  override fun recordedReviewRunId(passNumber: Int): String? =
    environment.state.recordFor(run.phaseId)
      ?.takeIf { (it.reviewPassNumber ?: 1) == passNumber }
      ?.reviewRunId
      ?.takeIf(String::isNotBlank)

  override fun startReview(
    iteration: Int,
    reviewRunId: String,
  ) {
    FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
      environment.request,
      environment.state,
      environment.recorder,
      environment.goalContinuationRecorder,
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
      environment,
      run.copy(goalReviewInput = input),
      null,
      null,
      prompt,
    )
  }

  override fun reviewLaunched(iteration: Int) {
    environment.observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
  }

  override fun recordReviewContentIdentities() {
    FeatureTaskRuntimeRunLoopLaunch.capturePhaseContentIdentities(
      environment.request,
      environment.session,
      environment.phaseGates,
      run.phaseId,
    )
  }

  override fun completedStepEnvelope(stepId: String): FeatureTaskRuntimeWorkflowArtifactMap? =
    environment.state.outputFor(stepId)?.normalizedOutput?.envelopeWireMap()

  override fun completedStepPayload(stepId: String): String? = environment.state.outputFor(stepId)?.payload

  override fun resolvedBranchName(): String? = environment.session.resolvedBranch

  override fun completedReviewPassCount(): Int? =
    environment.goalContinuationRecorder.reviewState(workflowId)?.completedPassCount

  override fun persistResolvedReviewTier(resolution: ReviewPassResolution) {
    FeatureTaskRuntimeRunLoopPhaseBlocking.persistResolvedReviewTier(
      environment.request,
      environment.goalContinuationRecorder,
      run,
      environment.state,
      resolution,
    )
  }

  override fun amendReviewRemediationCheckpoint(): Boolean =
    FeatureTaskRuntimeRunLoopCheckpointRemediation.checkpointEstablished(
      environment,
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
    environment.recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = workflowId,
        phaseId = run.phaseId,
        attempt = iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = environment.clock.instant(),
        byteSize = outputBytes.size.toLong(),
        sha256 = RejectedOutputDiagnosticService.sha256(outputBytes),
        payload = outputBytes,
        generation = environment.state.evidenceGeneration(run.policy.generationScoped),
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
        request = environment.request,
        state = environment.state,
        recorder = environment.recorder,
        observability = environment.observability,
        goalContinuationRecorder = environment.goalContinuationRecorder,
      )
    val args = PhaseReviewPersistenceArgs(run, iteration, environment.observability, fileManifest.toPhaseManifest())
    val blocked =
      with(FeatureTaskRuntimeRunLoopReviewCompletion) {
        if (isGoalReviewRun(run, environment.state)) {
          persistence.persistGoalReviewCompletion(args, output.normalizedOutput, output.repairEvidence)
        } else {
          persistence.persistStandaloneReviewCompletion(args, outputText, output)
        }
      }
    return blocked?.blockedReason
  }

  override fun stepCompleted(iteration: Int) {
    environment.observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  }

  override fun blockReviewPreparation(
    attemptCount: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    carriedOutput: AcceptedFeatureTaskRuntimePhaseOutput?,
  ) {
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
      environment.request,
      environment.state,
      environment.recorder,
      environment.goalContinuationRecorder.takeIf { isGoalReviewRun(run, environment.state) },
      BlockAndPersistArgs(
        run = run,
        attemptCount = attemptCount,
        reason = reason,
        observability = environment.observability,
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
      environment.request,
      environment.state,
      environment.recorder,
      environment.observability,
      PhaseBlockRequest(
        run = run,
        attemptCount = iteration,
        reason = reason,
        observability = environment.observability,
        payload = BlockAndPersistPayload(fileManifest = fileManifest?.toPhaseManifest()),
        failureDisposition = disposition,
      ),
    )
  }

  override fun isStepCompleted(stepId: String): Boolean = environment.state.isComplete(stepId)

  override fun isEvidenceInvalidated(stepId: String): Boolean =
    stepId in environment.state.phasesRequiringDurableGateInvalidation()

  override fun reviewedCheckpointFingerprint(reviewStepId: String): String? =
    FeatureTaskRuntimeRunLoopPhaseBlocking.reviewedCheckpointFingerprint(
      environment.request,
      environment.recorder,
      reviewStepId,
    )

  override fun persistReviewGenerationInvalidation(reviewStepId: String): Int? =
    environment.recorder.persistReviewGenerationInvalidation(workflowId, reviewStepId)

  override fun advanceReviewGeneration(
    generation: Int,
    reentryLoopId: String,
    reviewStepId: String,
  ) {
    environment.state.advanceReviewGeneration(generation)
    environment.state.resetInvalidatedReviewGeneration(reviewStepId)
    if (environment.session.pendingReentry?.loopId == reentryLoopId) {
      environment.session.transitionReentryPair(null, null)
    }
  }

  override fun completeReservedReviewPass(
    output: String,
    envelope: Map<String, Any?>,
  ): Boolean {
    val recordedVerdicts = environment.recorder.recordedFindingVerdicts(envelope)
    val findings = GoalSubtaskReviewSummaryReducer.fromOutput(envelope, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(envelope, findings)
    return environment.goalContinuationRecorder.completeGoalReviewPass(
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
                environment.request,
                environment.goalContinuationRecorder,
              ),
            ),
          commitFocusedAccounting = GoalSubtaskReviewSummaryReducer.commitFocusedAccounting(envelope),
        ),
    ) != null
  }

  override fun settleCarriedForwardReview(output: AcceptedFeatureTaskRuntimePhaseOutput) {
    val phaseId = run.phaseId
    if (environment.state.isComplete(phaseId)) {
      return
    }
    val reentry = environment.session.activeReentry
    val normalizedOutput = output.normalizedOutput
    val iteration = environment.state.nextIteration(phaseId)
    val priorRecord = environment.state.recordFor(phaseId)
    val persisted =
      environment.recorder.recordCompletedPhase(
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
    if (reentry != null) environment.session.transitionPendingReentry(null)
    environment.state.recordCompleted(
      FeatureTaskRuntimePhaseOutput(
        phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        output.repairEvidence,
      ),
    )
  }

  private fun PhaseStepFileManifest.toPhaseManifest() = FeatureTaskRuntimePhaseFileManifest(before, after)
}
