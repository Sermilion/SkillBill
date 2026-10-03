package skillbill.engine.featuretask.runloop.state

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.continuation.GoalReviewPassCompletionRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.core.BlockAndPersistArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch
import skillbill.engine.featuretask.runloop.core.LaunchRequiredWriteRejected
import skillbill.engine.featuretask.runloop.core.PersistPhaseArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseReviewPersistenceArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.finalization.FeatureTaskRuntimeRunLoopCommitCycle
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputPersistence
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopReviewCompletion
import skillbill.engine.featuretask.runloop.output.ReviewOutputPersistenceContext
import skillbill.engine.featuretask.runloop.output.isGoalReviewRun
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptOnce
import skillbill.engine.featuretask.slot.attempt.PhaseLaunchPreparation.prepareLaunchForCapture
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentStepBinding
import skillbill.engine.featuretask.slot.state.PhaseCommitStepBinding
import skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhasePlanningBriefingBinding
import skillbill.engine.featuretask.slot.state.PhasePlanningStepBinding
import skillbill.engine.featuretask.slot.state.PhasePullRequestContext
import skillbill.engine.featuretask.slot.state.PhasePullRequestStepBinding
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.engine.featuretask.slot.state.PhaseRepairReceiptState
import skillbill.engine.featuretask.slot.state.PhaseReviewExecutionContext
import skillbill.engine.featuretask.slot.state.PhaseReviewFindingObservations
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.review.ReviewPassResolution
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot

private open class FeatureTaskRuntimeRunLoopAgentStepBinding(
  protected val environment: PhaseAttemptLaunchCollaborationScope,
  protected val run: PhaseRun,
  protected val fanOutUnitId: Int? = null,
  protected val bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.stepBinding,
) : PhaseAcceptedStepExecution {
  private val stepLaunchState =
    FeatureTaskRuntimeRunLoopStepLaunchState(environment.acceptedLaunchState, run.phaseId)

  override val acceptedPhaseId: String get() = run.phaseId

  override val launchState: PhaseLaunchState get() = stepLaunchState

  protected val workflowId = environment.request.workflowId
  protected val repoRoot = environment.request.repoRoot
  private var active = true

  override fun requireAcceptedAttempt(
    run: PhaseRun,
    call: PhaseStepCall,
  ) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    val owner = environment.selectedOwnerOf(run.phaseId)
    check(
      run === this.run &&
        run.phaseId == this.run.phaseId &&
        active &&
        run.request === environment.request &&
        call.request === run.request &&
        call.description.step == run.phaseId &&
        owner != null &&
        owner.acceptsAttemptStrategy(call.strategyId) &&
        owner.policyFor(run.phaseId) == run.policy &&
        owner.policyFor(run.phaseId) == call.description.policy,
    ) {
      "Attempt does not match the accepted phase, request, strategy, and policy binding."
    }
  }

  override fun requireAcceptedStep(
    run: PhaseRun,
    strategyId: String,
  ) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    val owner = environment.selectedOwnerOf(run.phaseId)
    check(
      run === this.run &&
        active &&
        run.phaseId == this.run.phaseId &&
        run.request === environment.request &&
        owner != null &&
        owner.acceptsAttemptStrategy(strategyId) &&
        owner.policyFor(run.phaseId) == run.policy,
    ) {
      "Step operation does not match the accepted phase, request, strategy, and policy binding."
    }
  }

  override fun finishStepExecution() {
    if (active) {
      bindingCoordinator.endStepBinding(run, fanOutUnitId)
    }
    active = false
  }

  override fun nextStepIteration(): Int = environment.progress.phase(run.phaseId).nextIteration

  override fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = environment.recorder.loadResolvedBranch(workflowId)

  protected fun requireAcceptedPlanObservationStep(stepId: String) {
    check(stepId == acceptedPhaseId || environment.selectedOwnerOf(stepId) != null) {
      "Step '$stepId' is not in the accepted execution plan for this binding."
    }
  }

  override fun completedStepEnvelope(stepId: String): FeatureTaskRuntimeWorkflowArtifactMap? {
    requireAcceptedPlanObservationStep(stepId)
    return environment.progress
      .phase(stepId).output
      ?.normalizedOutput
      ?.envelopeWireMap()
  }

  override fun completedStepPayload(stepId: String): String? {
    requireAcceptedPlanObservationStep(stepId)
    return environment.progress.phase(stepId).output?.payload
  }

  override val resolvedBranchName: String? get() = environment.session.resolvedBranch

  override fun stepCompleted(iteration: Int) {
    environment.observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  }

  override fun isStepCompleted(stepId: String): Boolean {
    requireAcceptedPlanObservationStep(stepId)
    return environment.progress.phase(stepId).completed
  }

  override fun isEvidenceInvalidated(stepId: String): Boolean {
    requireAcceptedPlanObservationStep(stepId)
    return stepId in environment.progress.phasesRequiringDurableGateInvalidation
  }
}

private open class FeatureTaskRuntimeRunLoopLaunchingStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator = environment.stepBinding,
) : FeatureTaskRuntimeRunLoopAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseAgentExecution {
  override fun runAcceptedAgentStep(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome {
    requireAcceptedAttempt(run, call)
    return environment.runAcceptedAttemptLoop(run, call)
  }
}

private open class FeatureTaskRuntimeRunLoopPlanningAgentStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhasePlanningBriefingBinding,
  PhaseAgentStepBinding {
  override fun recordPlanningBriefing(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    attempt: Int,
  ): RequiredPhaseWrite {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    check(
      environment.selectedOwnerOf(acceptedPhaseId)?.executionBindingKind(acceptedPhaseId) ==
        PhaseExecutionBindingKind.PLANNING,
    )
    check(briefing.phaseId == acceptedPhaseId)
    return environment.recorder.recordPhaseBriefing(workflowId, briefing, null, attempt)
  }
}

private open class FeatureTaskRuntimeRunLoopMarkedAgentStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseAgentStepBinding

private class FeatureTaskRuntimeRunLoopQualityGateStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int?,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
) : FeatureTaskRuntimeRunLoopAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseQualityGateStepBinding {
  override fun runSelectedQualityGate(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome {
    requireAcceptedAttempt(run, call)
    val operation = requireNotNull(environment.selectedOwnerOf(run.phaseId)?.qualityGateOperation)
    val context = environment
    return when (operation) {
      is PhaseQualityGateOperation.PackGate ->
        context.qualityGateCycles.runPackGate(
          context,
          call,
          run,
          operation.commandFamily,
        )
      PhaseQualityGateOperation.AgentValidation -> context.qualityGateCycles.runAgentValidation(context, call, run)
    }
  }
}

private class FeatureTaskRuntimeRunLoopFinalizationStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int?,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
) : FeatureTaskRuntimeRunLoopAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseCommitStepBinding {
  override fun runCommitPush(run: PhaseRun): PhaseOutcome {
    val owner = requireNotNull(environment.selectedOwnerOf(run.phaseId))
    requireAcceptedStep(run, owner.strategyId)
    return with(FeatureTaskRuntimeRunLoopCommitCycle) {
      environment.runDeclaredCommitPushCycle(run)
    }
  }
}

private class FeatureTaskRuntimeRunLoopPullRequestStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int?,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
) : FeatureTaskRuntimeRunLoopMarkedAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhasePullRequestStepBinding {
  override fun pullRequestContext(): PhasePullRequestContext {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return PhasePullRequestContext(
      environment.request,
      environment.gitOperations.repositoryObservations(),
      environment.diagnostics,
      environment.lifecycleTelemetry::prDescriptionGenerated,
      environment.transitionDeclaration,
      environment.recorder.loadResolvedBranch(workflowId),
    )
  }
}

private class FeatureTaskRuntimeRunLoopPlanningStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.stepBinding,
) : FeatureTaskRuntimeRunLoopPlanningAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhasePlanningStepBinding {
  override fun fanOut(stepId: String): PhaseRunFanOut {
    check(stepId == acceptedPhaseId) { "Fan-out belongs to the accepted planning step '$acceptedPhaseId'." }
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return environment.fanOut(stepId)
  }

  override fun authorizeFanOutWave(run: PhaseRun) {
    environment.stepBinding.authorizeFanOutWave(run)
  }

  override fun releaseFanOutWave(run: PhaseRun) {
    environment.stepBinding.releaseFanOutWave(run)
  }
}

private class FeatureTaskRuntimeRunLoopReviewStepBinding(
  private val remediationContext: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    remediationContext.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(remediationContext, run, fanOutUnitId, bindingCoordinator),
  PhaseReviewStepBinding,
  PhaseReviewFindingObservations by FeatureTaskRuntimeRunLoopFindingVerificationState(
    remediationContext,
    run,
    fanOutUnitId,
    bindingCoordinator,
  ) {
  override fun startReviewStep(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome? {
    bindingCoordinator.requireActiveStepBinding(this.run, fanOutUnitId)
    check(run === this.run)
    return when (val start = PhaseAttemptOnce.persistRequiredStart(environment, run, iteration)) {
      is RequiredPhaseWrite.Acknowledged -> null
      is RequiredPhaseWrite.Rejected -> blockRequiredReviewWrite(start)
    }
  }

  override fun blockRequiredReviewWrite(rejection: RequiredPhaseWrite.Rejected): PhaseOutcome {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return PhaseAttemptOnce.blockRequiredWriteRejection(environment, run, rejection)
  }

  override fun reviewExecutionContext(): PhaseReviewExecutionContext {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return PhaseReviewExecutionContext(
      environment.gitOperations.repositoryObservations(),
      environment.clock,
    )
  }

  override fun recordReviewRun(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  ) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    remediationContext.recordReviewRunForAcceptedStep(reviewRunId, result, laneTelemetryRecorded)
  }

  override fun pinnedReviewTarget(resolve: () -> ReviewTarget): ReviewTarget {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return remediationContext.pinnedReviewTargetForAcceptedStep(resolve)
  }

  override fun reserveReviewPass(): GoalSubtaskReviewPassReservation {
    requireActiveReviewBinding()
    return environment.goalContinuationRecorder.reserveGoalReviewPass(workflowId)
  }

  override fun prepareGoalReviewInput(
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation =
    run {
      requireActiveReviewBinding()
      environment.goalContinuationRecorder.buildGoalReviewInput(
        workflowId = workflowId,
        gitOperations = environment.gitOperations,
        repoRoot = repoRoot,
        scopedUntrackedExclusions = scopedUntrackedExclusions,
        ownedPathspec = ownedPathspec,
      )
    }

  override fun carriedForwardReviewResult(): String? {
    requireActiveReviewBinding()
    return environment.goalContinuationRecorder.lastGoalReviewResult(workflowId)
  }

  override fun completeCarriedForwardReview(
    iteration: Int,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): String? {
    requireActiveReviewBinding()
    val normalizedOutput = output
    val phaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        environment.request,
        environment.settlementCoupling().progress,
        environment.goalContinuationRecorder,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(run, iteration, STATUS_COMPLETED, true, normalizedOutput.canonicalJson),
          extras =
            PhaseStateRequestAttachments(normalizedOutput = normalizedOutput, repairEvidence = null),
        ),
      )
    val prefix = "Carried-forward goal review could not atomically persist its canonical result."
    return runCatching {
      environment.coupledRunTransitions.persistAuthoritativePhaseCompletion(
        recorder = environment.recorder,
        phaseState = phaseState,
        inMemoryOutput =
          FeatureTaskRuntimePhaseOutput(
            run.phaseId,
            iteration,
            normalizedOutput.canonicalJson,
            normalizedOutput,
            null,
          ),
      )
    }.fold(
      onSuccess = { persisted ->
        if (persisted) {
          null
        } else {
          prefix
        }
      },
      onFailure = { error -> "$prefix ${error.message.orEmpty()}" },
    )
  }

  override val reviewPassNumber: Int get() {
    requireActiveReviewBinding()
    return FeatureTaskRuntimeRunLoopPhaseBlocking.reviewPassNumber(
      environment.request,
      environment.goalContinuationRecorder,
      run,
      environment.settlementCoupling().progress,
    ) ?: 1
  }

  override fun recordedReviewRunId(passNumber: Int): String? {
    requireActiveReviewBinding()
    return environment.progress
      .phase(run.phaseId).record
      ?.takeIf { (it.reviewPassNumber ?: 1) == passNumber }
      ?.reviewRunId
      ?.takeIf(String::isNotBlank)
  }

  override fun startReview(
    iteration: Int,
    reviewRunId: String,
  ): RequiredPhaseWrite {
    requireActiveReviewBinding()
    return FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
      environment,
      environment.goalContinuationRecorder,
      PersistPhaseArgs(
        write = PhaseStateWriteArgs(run, iteration, STATUS_RUNNING, false, null),
        reviewRunId = reviewRunId,
      ),
    )
  }

  override fun prepareReviewBriefing(
    iteration: Int,
    prompt: PhaseStepPromptSource,
    input: GoalSubtaskReviewInput,
  ): RequiredPhaseWrite {
    requireActiveReviewBinding()
    val preparation =
      environment.prepareLaunchForCapture(
        run.copy(goalReviewInput = input),
        iteration,
        null,
        prompt,
        this,
      )
    return (preparation as? LaunchRequiredWriteRejected)?.rejection ?: RequiredPhaseWrite.Acknowledged
  }

  override fun reviewLaunched(iteration: Int) {
    requireActiveReviewBinding()
    environment.observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
  }

  override fun recordReviewContentIdentities() {
    requireActiveReviewBinding()
    FeatureTaskRuntimeRunLoopLaunch.capturePhaseContentIdentities(
      environment.request,
      environment.coupledRunTransitions,
      environment.gitOperations,
      run.phaseId,
    )
  }

  override val completedReviewPassCount: Int? get() {
    requireActiveReviewBinding()
    return environment.goalContinuationRecorder.reviewState(workflowId)?.completedPassCount
  }

  override fun persistResolvedReviewTier(resolution: ReviewPassResolution) {
    requireActiveReviewBinding()
    FeatureTaskRuntimeRunLoopPhaseBlocking.persistResolvedReviewTier(
      environment.request,
      environment.goalContinuationRecorder,
      run,
      environment.settlementCoupling().progress,
      resolution,
    )
  }

  override fun amendReviewRemediationCheckpoint(): Boolean {
    requireActiveReviewBinding()
    return FeatureTaskRuntimeRunLoopCheckpointRemediation.checkpointEstablished(
      remediationContext,
      precedingPhaseId = run.phaseId,
      loopId = null,
      intent = FeatureTaskRuntimeCheckpointMessage.INTENT_REMEDIATION,
      blockedReason = { branch, error ->
        "Feature-task-runtime could not amend review changes on the feature branch '$branch'" +
          (if (error.isBlank()) "." else " ($error).") +
          " Refusing to complete review with uncommitted review fixes."
      },
    )
  }

  override fun retainReviewOutput(
    iteration: Int,
    outputText: String,
  ) {
    requireActiveReviewBinding()
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
        generation = (if (run.policy.generationScoped) environment.progress.reviewEvidenceGeneration else 0),
      ),
    )
  }

  override fun completeReview(
    iteration: Int,
    outputText: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
    fileManifest: PhaseStepFileManifest,
  ): String? {
    requireActiveReviewBinding()
    val coupling = environment.settlementCoupling()
    val persistence =
      ReviewOutputPersistenceContext(
        request = environment.request,
        state = coupling.progress,
        transitions = coupling.transitions,
        session = coupling.session,
        recorder = environment.recorder,
        observability = environment.observability,
        goalContinuationRecorder = environment.goalContinuationRecorder,
      )
    val args = PhaseReviewPersistenceArgs(run, iteration, environment.observability, fileManifest.toPhaseManifest())
    val blocked =
      with(FeatureTaskRuntimeRunLoopReviewCompletion) {
        if (isGoalReviewRun(run, environment.settlementCoupling().progress)) {
          persistence.persistGoalReviewCompletion(args, output, null)
        } else {
          persistence.persistStandaloneReviewCompletion(args, outputText, output)
        }
      }
    return blocked?.blockedReason
  }

  override fun blockReviewPreparation(
    attemptCount: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    carriedOutput: NormalizedFeatureTaskRuntimePhaseOutput?,
  ) {
    requireActiveReviewBinding()
    val coupling = environment.settlementCoupling()
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
      coupling.progress,
      coupling.transitions,
      environment.recorder,
      environment.goalContinuationRecorder.takeIf { isGoalReviewRun(run, coupling.progress) },
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
              normalizedOutput = it,
              outputArtifact = it.canonicalJson,
              repairEvidence = null,
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
    requireActiveReviewBinding()
    val coupling = environment.settlementCoupling()
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      coupling.progress,
      coupling.transitions,
      environment.recorder,
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

  override fun reviewedCheckpointFingerprint(reviewStepId: String): String? {
    requireActiveReviewBinding()
    check(reviewStepId == acceptedPhaseId)
    return FeatureTaskRuntimeRunLoopPhaseBlocking.reviewedCheckpointFingerprint(
      environment.request,
      environment.recorder,
      reviewStepId,
    )
  }

  override fun invalidateReviewGeneration(
    reviewStepId: String,
    reentryLoopId: String,
  ): Int? {
    requireActiveReviewBinding()
    val loopRules = environment.selectedOwnerOf(acceptedPhaseId)?.loopRules
    check(reviewStepId == acceptedPhaseId && loopRules?.resumesInFlightReentry(reentryLoopId) == true)
    return environment.coupledRunTransitions.persistReviewGenerationInvalidation(
      recorder = environment.recorder,
      workflowId = workflowId,
      reviewStepId = reviewStepId,
      reentryLoopId = reentryLoopId,
    )
  }

  override fun completeReservedReviewPass(
    output: String,
    envelope: Map<String, Any?>,
  ): Boolean {
    requireActiveReviewBinding()
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

  override fun settleCarriedForwardReview(output: NormalizedFeatureTaskRuntimePhaseOutput) {
    requireActiveReviewBinding()
    val phaseId = run.phaseId
    if (environment.progress.phase(phaseId).completed) {
      return
    }
    val reentry = environment.session.activeReentry
    val normalizedOutput = output
    val iteration = environment.progress.phase(phaseId).nextIteration
    val priorRecord = environment.progress.phase(phaseId).record
    val inMemoryOutput =
      FeatureTaskRuntimePhaseOutput(
        phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        null,
      )
    val phaseState =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = workflowId,
        phaseId = phaseId,
        status = STATUS_COMPLETED,
        attemptCount = iteration,
        resolvedAgentId = priorRecord?.resolvedAgentId ?: "user-directed",
        finished = true,
        outputArtifact = normalizedOutput.canonicalJson,
        normalizedOutput = normalizedOutput,
        repairEvidence = null,
        loopId = reentry?.loopId,
        edgeIteration = reentry?.edgeIteration,
      )
    val persisted =
      environment.coupledRunTransitions.persistCarriedForwardPhaseCompletion(
        recorder = environment.recorder,
        phaseState = phaseState,
        inMemoryOutput = inMemoryOutput,
        clearPendingReentry = reentry != null,
      )
    if (!persisted) {
      error("Carried-forward goal review could not atomically persist its canonical result.")
    }
  }

  private fun PhaseStepFileManifest.toPhaseManifest() = FeatureTaskRuntimePhaseFileManifest(before, after)

  private fun requireActiveReviewBinding() {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    check(
      environment.selectedOwnerOf(acceptedPhaseId)?.executionBindingKind(acceptedPhaseId) ==
        PhaseExecutionBindingKind.REVIEW,
    ) {
      "Review operations belong to the accepted review step."
    }
  }
}

private class FeatureTaskRuntimeRunLoopVerifyFindingsStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseAgentStepBinding,
  PhaseVerifyFindingsStepBinding,
  PhaseFindingVerificationState by FeatureTaskRuntimeRunLoopFindingVerificationState(
    environment,
    run,
    fanOutUnitId,
    bindingCoordinator,
  )

private class FeatureTaskRuntimeRunLoopImplementFixStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseAgentStepBinding,
  PhaseImplementFixStepBinding,
  PhaseRepairReceiptState by FeatureTaskRuntimeRunLoopFindingVerificationState(
    environment,
    run,
    fanOutUnitId,
    bindingCoordinator,
  )

internal object FeatureTaskRuntimeRunLoopStepBindings {
  fun create(
    launchEnvironment: PhaseAttemptLaunchCollaborationScope,
    run: PhaseRun,
    fanOutUnitId: Int? = null,
    bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
      launchEnvironment.stepBinding,
  ): PhaseAcceptedStepExecution {
    val owner = launchEnvironment.selectedOwnerOf(run.phaseId)
    return when {
      owner?.slot == PhaseSlot.CODE_REVIEW ->
        when (owner.executionBindingKind(run.phaseId)) {
          PhaseExecutionBindingKind.REVIEW -> {
            val remediationEnvironment = launchEnvironment
            FeatureTaskRuntimeRunLoopReviewStepBinding(
              remediationEnvironment,
              run,
              fanOutUnitId,
              bindingCoordinator,
            )
          }
          PhaseExecutionBindingKind.FINDING_VERIFICATION -> {
            val remediationEnvironment = launchEnvironment
            FeatureTaskRuntimeRunLoopVerifyFindingsStepBinding(
              remediationEnvironment,
              run,
              fanOutUnitId,
              bindingCoordinator,
            )
          }
          PhaseExecutionBindingKind.REPAIR_RECEIPT -> {
            val remediationEnvironment = launchEnvironment
            FeatureTaskRuntimeRunLoopImplementFixStepBinding(
              remediationEnvironment,
              run,
              fanOutUnitId,
              bindingCoordinator,
            )
          }
          else ->
            FeatureTaskRuntimeRunLoopMarkedAgentStepBinding(
              launchEnvironment,
              run,
              fanOutUnitId,
              bindingCoordinator,
            )
        }
      owner?.qualityGateOperation != null ->
        FeatureTaskRuntimeRunLoopQualityGateStepBinding(launchEnvironment, run, fanOutUnitId, bindingCoordinator)
      owner?.slot == PhaseSlot.PULL_REQUEST ->
        FeatureTaskRuntimeRunLoopPullRequestStepBinding(launchEnvironment, run, fanOutUnitId, bindingCoordinator)
      owner?.slot == PhaseSlot.COMMIT_PUSH ->
        FeatureTaskRuntimeRunLoopFinalizationStepBinding(launchEnvironment, run, fanOutUnitId, bindingCoordinator)
      owner?.plansInFanOut == true && fanOutUnitId == null ->
        FeatureTaskRuntimeRunLoopPlanningStepBinding(
          launchEnvironment,
          run,
          fanOutUnitId,
          bindingCoordinator,
        )
      owner?.executionBindingKind(run.phaseId) == PhaseExecutionBindingKind.PLANNING ->
        FeatureTaskRuntimeRunLoopPlanningAgentStepBinding(launchEnvironment, run, fanOutUnitId, bindingCoordinator)
      else ->
        FeatureTaskRuntimeRunLoopMarkedAgentStepBinding(
          launchEnvironment,
          run,
          fanOutUnitId,
          bindingCoordinator,
        )
    }
  }
}
