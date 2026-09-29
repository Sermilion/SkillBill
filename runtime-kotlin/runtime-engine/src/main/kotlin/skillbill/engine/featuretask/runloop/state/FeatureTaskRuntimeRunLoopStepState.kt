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
import skillbill.engine.featuretask.runloop.core.PersistPhaseArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseReviewPersistenceArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.finalization.RuntimeCommitCycle
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputPersistence
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopReviewCompletion
import skillbill.engine.featuretask.runloop.output.ReviewOutputPersistenceContext
import skillbill.engine.featuretask.runloop.output.isGoalReviewRun
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.qualitygate.AgentValidateGateCycle
import skillbill.engine.featuretask.runloop.qualitygate.PackBuildGateCycle
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepLaunchState
import skillbill.engine.featuretask.runloop.state.pinnedReviewTargetForAcceptedStep
import skillbill.engine.featuretask.runloop.state.recordReviewRunForAcceptedStep
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptOnce
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRemediationCollaborationScope
import skillbill.engine.featuretask.slot.attempt.PhaseLaunchPreparation
import skillbill.engine.featuretask.slot.attempt.PhaseQualityGateCycleScope
import skillbill.engine.featuretask.slot.attempt.PhaseRuntimeFinalizationScope
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.attempt.remediationCollaborationScope
import skillbill.engine.featuretask.slot.attempt.runLoopBinding
import skillbill.engine.featuretask.slot.codereview.PhaseReviewExecutionContext
import skillbill.engine.featuretask.slot.plan.GoalPlanFanOutStrategy
import skillbill.engine.featuretask.slot.pullrequest.PhasePullRequestContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentExecution
import skillbill.engine.featuretask.slot.state.PhaseAgentStepBinding
import skillbill.engine.featuretask.slot.state.PhaseCommitStepBinding
import skillbill.engine.featuretask.slot.state.PhaseFindingVerificationState
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhasePlanningBriefingBinding
import skillbill.engine.featuretask.slot.state.PhasePlanningStepBinding
import skillbill.engine.featuretask.slot.state.PhasePullRequestStepBinding
import skillbill.engine.featuretask.slot.state.PhaseQualityGateStepBinding
import skillbill.engine.featuretask.slot.state.PhaseRepairReceiptState
import skillbill.engine.featuretask.slot.state.PhaseReviewFindingObservations
import skillbill.engine.featuretask.slot.state.PhaseReviewStepBinding
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.engine.featuretask.slot.state.PhaseVerifyFindingsStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
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
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

private open class FeatureTaskRuntimeRunLoopAgentStepBinding(
  protected val environment: PhaseAttemptLaunchCollaborationScope,
  protected val run: PhaseRun,
  protected val fanOutUnitId: Int? = null,
  protected val bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.runLoopBinding.stepBinding,
) : PhaseAcceptedStepExecution {
  private val runLoop = environment.runLoopBinding
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
    val owner = runLoop.selectedOwnerOf(run.phaseId)
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
    val owner = runLoop.selectedOwnerOf(run.phaseId)
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

  override fun nextStepIteration(): Int = environment.progress.nextIteration(run.phaseId)

  override fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = environment.recorder.loadResolvedBranch(workflowId)

  protected fun requireAcceptedPlanObservationStep(stepId: String) {
    check(stepId == acceptedPhaseId || runLoop.selectedOwnerOf(stepId) != null) {
      "Step '$stepId' is not in the accepted execution plan for this binding."
    }
  }

  override fun completedStepEnvelope(stepId: String): FeatureTaskRuntimeWorkflowArtifactMap? {
    requireAcceptedPlanObservationStep(stepId)
    return environment.progress
      .outputFor(stepId)
      ?.normalizedOutput
      ?.envelopeWireMap()
  }

  override fun completedStepPayload(stepId: String): String? {
    requireAcceptedPlanObservationStep(stepId)
    return environment.progress.outputFor(stepId)?.payload
  }

  override fun resolvedBranchName(): String? = environment.session.resolvedBranch

  override fun stepCompleted(iteration: Int) {
    environment.observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
  }

  override fun isStepCompleted(stepId: String): Boolean {
    requireAcceptedPlanObservationStep(stepId)
    return environment.progress.isComplete(stepId)
  }

  override fun isEvidenceInvalidated(stepId: String): Boolean {
    requireAcceptedPlanObservationStep(stepId)
    return stepId in environment.progress.phasesRequiringDurableGateInvalidation()
  }
}

private open class FeatureTaskRuntimeRunLoopLaunchingStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator = environment.runLoopBinding.stepBinding,
) : FeatureTaskRuntimeRunLoopAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseAgentExecution {
  override fun runAcceptedAgentStep(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome {
    requireAcceptedAttempt(run, call)
    return environment.runLoopBinding.runAcceptedAgentStep(run, call)
  }
}

private open class FeatureTaskRuntimeRunLoopPlanningAgentStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.runLoopBinding.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhasePlanningBriefingBinding,
  PhaseAgentStepBinding {
  override fun recordPlanningBriefing(
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    attempt: Int,
  ) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    check(
      acceptedPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN ||
        acceptedPhaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    )
    check(briefing.phaseId == acceptedPhaseId)
    environment.recorder.recordPhaseBriefing(workflowId, briefing, null, attempt)
  }
}

private open class FeatureTaskRuntimeRunLoopMarkedAgentStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.runLoopBinding.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseAgentStepBinding

private class QualityGateStepBinding(
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
    val operation = requireNotNull(environment.runLoopBinding.selectedOwnerOf(run.phaseId)?.qualityGateOperation)
    val context = PhaseQualityGateCycleScope(environment.attemptRunHost())
    return when (operation) {
      is PhaseQualityGateOperation.PackGate -> PackBuildGateCycle(context, call, operation.commandFamily).run(run)
      PhaseQualityGateOperation.AgentValidation -> AgentValidateGateCycle(context, call).run(run)
    }
  }
}

private class FinalizationStepBinding(
  environment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int?,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator,
) : FeatureTaskRuntimeRunLoopAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseCommitStepBinding {
  override fun runCommitPush(run: PhaseRun): PhaseOutcome {
    val owner = requireNotNull(environment.runLoopBinding.selectedOwnerOf(run.phaseId))
    requireAcceptedStep(run, owner.strategyId)
    return with(RuntimeCommitCycle) {
      PhaseRuntimeFinalizationScope(environment.attemptRunHost()).runDeclaredCommitPushCycle(run)
    }
  }
}

private class PullRequestStepBinding(
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
      environment.phaseGates.gitOperations.repositoryObservations(),
      environment.diagnostics,
      environment.phaseGates.lifecycleTelemetry::prDescriptionGenerated,
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
    environment.runLoopBinding.stepBinding,
) : FeatureTaskRuntimeRunLoopPlanningAgentStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhasePlanningStepBinding {
  private val runLoop = environment.runLoopBinding

  override fun fanOut(stepId: String): PhaseRunFanOut {
    check(stepId == acceptedPhaseId) { "Fan-out belongs to the accepted planning step '$acceptedPhaseId'." }
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return runLoop.fanOut(stepId)
  }

  override fun authorizeFanOutWave(run: PhaseRun) {
    runLoop.stepBinding.authorizeFanOutWave(run)
  }

  override fun releaseFanOutWave(run: PhaseRun) {
    runLoop.stepBinding.releaseFanOutWave(run)
  }
}

private class FeatureTaskRuntimeRunLoopReviewStepBinding(
  private val remediationContext: PhaseAttemptRemediationCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    remediationContext.runLoopBinding.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(remediationContext, run, fanOutUnitId, bindingCoordinator),
  PhaseReviewStepBinding,
  PhaseReviewFindingObservations by FeatureTaskRuntimeRunLoopFindingVerificationState(
    remediationContext,
    run,
    fanOutUnitId,
    bindingCoordinator,
  ) {
  private val runLoop = remediationContext.runLoopBinding

  override fun startReviewStep(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome? {
    bindingCoordinator.requireActiveStepBinding(this.run, fanOutUnitId)
    check(run === this.run)
    return try {
      PhaseAttemptOnce.persistRequiredStart(environment, run, iteration)
      null
    } catch (rejection: RequiredPhaseWriteRejected) {
      PhaseAttemptOnce.blockRequiredWriteRejection(environment, run, rejection)
    }
  }

  override fun reviewExecutionContext(): PhaseReviewExecutionContext {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return PhaseReviewExecutionContext(
      environment.phaseGates.gitOperations.repositoryObservations(),
      environment.outputValidator,
      environment.clock,
    )
  }

  override fun recordReviewRun(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  ) {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    remediationContext.attemptRunHost().recordReviewRunForAcceptedStep(reviewRunId, result, laneTelemetryRecorded)
  }

  override fun pinnedReviewTarget(resolve: () -> ReviewTarget): ReviewTarget {
    bindingCoordinator.requireActiveStepBinding(run, fanOutUnitId)
    return remediationContext.attemptRunHost().pinnedReviewTargetForAcceptedStep(resolve)
  }

  override fun reserveReviewPass(): GoalSubtaskReviewPassReservation =
    environment.goalContinuationRecorder.reserveGoalReviewPass(workflowId)

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
        environment.settlementCoupling().progress,
        environment.goalContinuationRecorder,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(run, iteration, STATUS_COMPLETED, true, normalizedOutput.canonicalJson),
          extras =
            PhaseStateRequestAttachments(normalizedOutput = normalizedOutput, repairEvidence = output.repairEvidence),
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
            output.repairEvidence,
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

  override fun reviewPassNumber(): Int =
    FeatureTaskRuntimeRunLoopPhaseBlocking.reviewPassNumber(
      environment.request,
      environment.goalContinuationRecorder,
      run,
      environment.settlementCoupling().progress,
    ) ?: 1

  override fun recordedReviewRunId(passNumber: Int): String? =
    environment.progress
      .recordFor(run.phaseId)
      ?.takeIf { (it.reviewPassNumber ?: 1) == passNumber }
      ?.reviewRunId
      ?.takeIf(String::isNotBlank)

  override fun startReview(
    iteration: Int,
    reviewRunId: String,
  ) {
    FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
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
  ) {
    PhaseLaunchPreparation.prepareLaunchForCapture(
      environment,
      run.copy(goalReviewInput = input),
      iteration,
      null,
      prompt,
      this,
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
      environment.coupledRunTransitions,
      environment.phaseGates,
      run.phaseId,
    )
  }

  override fun completedReviewPassCount(): Int? =
    environment.goalContinuationRecorder.reviewState(workflowId)?.completedPassCount

  override fun persistResolvedReviewTier(resolution: ReviewPassResolution) {
    FeatureTaskRuntimeRunLoopPhaseBlocking.persistResolvedReviewTier(
      environment.request,
      environment.goalContinuationRecorder,
      run,
      environment.settlementCoupling().progress,
      resolution,
    )
  }

  override fun amendReviewRemediationCheckpoint(): Boolean =
    FeatureTaskRuntimeRunLoopCheckpointRemediation.checkpointEstablished(
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
        generation = environment.progress.evidenceGeneration(run.policy.generationScoped),
      ),
    )
  }

  override fun completeReview(
    iteration: Int,
    outputText: String,
    output: AcceptedFeatureTaskRuntimePhaseOutput,
    fileManifest: PhaseStepFileManifest,
  ): String? {
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
          persistence.persistGoalReviewCompletion(args, output.normalizedOutput, output.repairEvidence)
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
    carriedOutput: AcceptedFeatureTaskRuntimePhaseOutput?,
  ) {
    val coupling = environment.settlementCoupling()
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
      environment.request,
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
    val coupling = environment.settlementCoupling()
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      environment.request,
      coupling.progress,
      coupling.transitions,
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

  override fun reviewedCheckpointFingerprint(reviewStepId: String): String? {
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
    check(
      reviewStepId == acceptedPhaseId &&
        reentryLoopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
    )
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
    if (environment.progress.isComplete(phaseId)) {
      return
    }
    val reentry = environment.session.activeReentry
    val normalizedOutput = output.normalizedOutput
    val iteration = environment.progress.nextIteration(phaseId)
    val priorRecord = environment.progress.recordFor(phaseId)
    val inMemoryOutput =
      FeatureTaskRuntimePhaseOutput(
        phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        output.repairEvidence,
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
        repairEvidence = output.repairEvidence,
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
}

private class FeatureTaskRuntimeRunLoopVerifyFindingsStepBinding(
  environment: PhaseAttemptRemediationCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.runLoopBinding.stepBinding,
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
  environment: PhaseAttemptRemediationCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    environment.runLoopBinding.stepBinding,
) : FeatureTaskRuntimeRunLoopLaunchingStepBinding(environment, run, fanOutUnitId, bindingCoordinator),
  PhaseAgentStepBinding,
  PhaseImplementFixStepBinding,
  PhaseRepairReceiptState by FeatureTaskRuntimeRunLoopFindingVerificationState(
    environment,
    run,
    fanOutUnitId,
    bindingCoordinator,
  )

internal fun featureTaskRuntimeRunLoopStepBinding(
  launchEnvironment: PhaseAttemptLaunchCollaborationScope,
  run: PhaseRun,
  fanOutUnitId: Int? = null,
  bindingCoordinator: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    launchEnvironment.runLoopBinding.stepBinding,
): PhaseAcceptedStepExecution {
  val owner = launchEnvironment.runLoopBinding.selectedOwnerOf(run.phaseId)
  return when {
    owner?.slot == PhaseSlot.CODE_REVIEW ->
      when (run.phaseId) {
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> {
          val remediationEnvironment = remediationCollaborationScope(launchEnvironment)
          FeatureTaskRuntimeRunLoopReviewStepBinding(
            remediationEnvironment,
            run,
            fanOutUnitId,
            bindingCoordinator,
          )
        }
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS -> {
          val remediationEnvironment = remediationCollaborationScope(launchEnvironment)
          FeatureTaskRuntimeRunLoopVerifyFindingsStepBinding(
            remediationEnvironment,
            run,
            fanOutUnitId,
            bindingCoordinator,
          )
        }
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> {
          val remediationEnvironment = remediationCollaborationScope(launchEnvironment)
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
      QualityGateStepBinding(launchEnvironment, run, fanOutUnitId, bindingCoordinator)
    owner?.slot == PhaseSlot.PULL_REQUEST ->
      PullRequestStepBinding(launchEnvironment, run, fanOutUnitId, bindingCoordinator)
    owner?.slot == PhaseSlot.COMMIT_PUSH ->
      FinalizationStepBinding(launchEnvironment, run, fanOutUnitId, bindingCoordinator)
    owner?.strategyId == GoalPlanFanOutStrategy.ID && fanOutUnitId == null ->
      FeatureTaskRuntimeRunLoopPlanningStepBinding(
        launchEnvironment,
        run,
        fanOutUnitId,
        bindingCoordinator,
      )
    owner?.slot == PhaseSlot.PREPLAN || owner?.slot == PhaseSlot.PLAN ->
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
