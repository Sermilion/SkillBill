package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.launchHookContext
import skillbill.engine.featuretask.runloop.attempt.phaseAttemptContext
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FixLoopBranchContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptAccumulatorContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptLoopState
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.observability.featureTaskRuntimeStartContinuationKind
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.state.AcceptanceCriterionRepairCycleRecords
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/** Runs the attempts of one step call and settles the step, so a run state decides how its steps launch. */
internal fun interface PhaseStepAttempts {
  /** Runs the attempts [call] makes for [run] and returns the step's outcome. */
  fun run(
    run: PhaseRun,
    call: PhaseStepCall,
    context: PhaseRunLoopAttemptScope,
  ): PhaseOutcome
}

internal object PhaseAttemptLoop : PhaseStepAttempts {
  override fun run(
    run: PhaseRun,
    call: PhaseStepCall,
    context: PhaseRunLoopAttemptScope,
  ): PhaseOutcome =
    with(PhaseAttemptSteps) {
      context.runPhaseAttempts(run, call)
    }
}

internal object PhaseAttemptSteps {
  private fun durableCriterionLaunchCount(
    recorder: PhaseRunRecords,
    run: PhaseRun,
  ): Int {
    if (run.reentry != null || run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT) return 0
    return try {
      recorder.loadImplementationProgress(run.request.workflowId)?.launchCount ?: 0
    } catch (_: SkillBillRuntimeException) {
      0
    }
  }

  fun PhaseRunLoopAttemptScope.runPhaseAttempts(
    run: PhaseRun,
    call: PhaseStepCall,
  ): PhaseOutcome {
    call.acceptedExecution.requireAcceptedAttempt(run, call)
    val agentId = run.resolvedAgent.resolvedAgentId
    val coupling = settlementCoupling()
    val progressState = coupling.progress
    var iteration = progress.phase(run.phaseId).nextIteration
    val continuationSegmentCount =
      FeatureTaskRuntimeRunLoopPhaseBlocking
        .durableContinuationSegmentCount(recorder, run) + durableCriterionLaunchCount(recorder, run)
    val nonOutputAttempts = FeatureTaskRuntimeRunLoopPhaseBlocking.durableNonOutputAttempts(progressState, run)
    when (val start = PhaseAttemptOnce.persistRequiredStart(this, run, iteration)) {
      is RequiredPhaseWrite.Acknowledged -> Unit
      is RequiredPhaseWrite.Rejected -> return PhaseAttemptOnce.blockRequiredWriteRejection(this, run, start)
    }
    val operatorReopened = FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(session, run.phaseId)
    val crashResumed = progress.phase(run.phaseId).resumedFromPriorProcess
    coupling.transitions.beginPhaseAttemptLaunchAfterRequiredStart(
      run.phaseId,
      operatorReopened = operatorReopened,
    )
    val semanticIteration =
      (
        progress.fixLoopIterationFor(run.phaseId, iteration) - continuationSegmentCount - nonOutputAttempts.size
      ).coerceAtLeast(1)
    stepHooks(run).onLaunch(run, launchHookContext(run, stepHooks(run)))
    observability.started(
      run.phaseId,
      agentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry(
        resumed = iteration > 1 || progress.phase(run.phaseId).hasPriorRecord,
        startKind =
          featureTaskRuntimeStartContinuationKind(
            crashResumed = crashResumed,
            verifierReentry =
              run.reentry?.let {
                transitions.backwardEdges
                  .firstOrNull { edge -> edge.loopId == it.loopId }
                  ?.destinationPhaseId == it.phaseId
              } == true,
            attemptCount = iteration,
          ),
      ),
    )
    var outcome: PhaseOutcome? = null
    val loop =
      PhaseAttemptLoopState(
        iteration = iteration,
        outputGateFailures = 0,
        semanticIteration = semanticIteration,
        continuationSegmentCount = continuationSegmentCount,
        requiredStartAcknowledged = true,
      )
    while (outcome == null) {
      outcome =
        resolveFixLoopOutcome(
          FixLoopOutcomeArgs(
            context =
              PhaseAttemptAccumulatorContext(phaseAttemptContext(run, loop.iteration, observability)),
            loop = loop,
            agentId = agentId,
            call = call,
          ),
        )
    }
    return outcome
  }

  fun PhaseRunLoopAttemptScope.resolveFixLoopOutcome(args: FixLoopOutcomeArgs): PhaseOutcome? {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val observability = args.context.attempt.observability
    val loop = args.loop
    val agentId = args.agentId
    val coupling = settlementCoupling()
    val attempt =
      PhaseAttemptOnce.attemptOnce(
        this@resolveFixLoopOutcome,
        recordRejectionAttemptArgs(
          phaseAttemptContext(
            run,
            loop.iteration,
            observability,
            loop.outputGateFailures,
          ),
          args.call,
          priorCorrection = loop.priorCorrection,
        ),
        requiredStartAlreadyAcknowledged = loop.requiredStartAcknowledged,
      )
    loop.requiredStartAcknowledged = false
    val context =
      FixLoopBranchContext(
        run,
        attempt,
        loop,
        stepHooks(run).serialAcceptanceRepairStep,
        observability,
        agentId,
        coupling.session,
        state,
        coupling.transitions,
      )
    val phaseAttempts = PhaseAttemptContinuations
    return attempt.settledOutcome ?: AcceptanceCriterionRepairAttemptSettlement.settle(recorder, context) ?: when {
      attempt.incompleteWorkContinuationReason != null ->
        phaseAttempts.settleIncompleteWork(
          recorder,
          context,
        )
      attempt.boundaryBodyDeliveryContinuationReason != null ->
        phaseAttempts.settleBoundaryBodyDelivery(observability, context)
      attempt.criterionTurnContinuationReason != null -> phaseAttempts.settleCriterionTurn(context)
      attempt.findingsOwed != null ->
        phaseAttempts.settleFindingsOwed(recorder, context, requireNotNull(attempt.findingsOwed))
      attempt.retryableTerminal != null ->
        phaseAttempts.settleRetryableTerminal(recorder, context, requireNotNull(attempt.retryableTerminal))
      else ->
        FeatureTaskRuntimeRunLoopPhaseBlocking.settleSemanticFailure(recorder, context)
    }
  }
}

internal object AcceptanceCriterionRepairAttemptSettlement {
  fun settle(
    recorder: PhaseRunRecords,
    context: FixLoopBranchContext,
  ): PhaseOutcome? {
    val run = context.run
    val scope = (run.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair
    val cycles = recorder as? AcceptanceCriterionRepairCycleRecords
    if (scope == null || !context.serialAcceptanceRepairStep || cycles?.isRepairStep(run.phaseId) != true) return null
    val attempt = context.attempt
    val normalized = serialRepairOutput(attempt) ?: return null
    val phase =
      FeatureTaskRuntimePhaseStateRequest(
        run.request.workflowId, run.phaseId, "completed", context.loop.iteration,
        run.resolvedAgent.resolvedAgentId, true,
        outputArtifact = normalized.canonicalJson,
        normalizedOutput = normalized,
        failureDisposition = attempt.retryableTerminal?.failureDisposition,
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
        mutating = run.policy.mutating,
      )
    val committed =
      if (attempt.retryableTerminal?.failureDisposition == FeatureTaskRuntimeFailureDisposition.RETRYABLE) {
        recorder.recordCompletedPhase(phase) == RequiredPhaseWrite.Acknowledged
      } else {
        val kind =
          if (attempt is AttemptResult.IncompleteWork) {
            AcceptanceCriterionRepairOutcomeKind.INCOMPLETE
          } else {
            AcceptanceCriterionRepairOutcomeKind.BLOCKED
          }
        cycles.settleUnsuccessfulRepair(phase, kind) ?: return null
      }
    return if (!committed) {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        context.progress,
        context.loopTransitions,
        recorder,
        PhaseBlockRequest(
          run,
          context.loop.iteration,
          "The repair outcome could not be committed. The reserved cycle remains unchanged.",
          context.observability,
          payload = BlockAndPersistPayload(normalizedOutput = normalized, fileManifest = attempt.fileManifest),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    } else {
      acknowledgeCommitted(context, phase, normalized, scope)
    }
  }

  private fun serialRepairOutput(attempt: AttemptResult): NormalizedFeatureTaskRuntimePhaseOutput? =
    when (attempt) {
      is AttemptResult.IncompleteWork -> attempt.normalizedOutput
      is AttemptResult.RetryableTerminal -> attempt.normalizedOutput
      else -> null
    }

  private fun acknowledgeCommitted(
    context: FixLoopBranchContext,
    phase: FeatureTaskRuntimePhaseStateRequest,
    normalized: NormalizedFeatureTaskRuntimePhaseOutput,
    scope: AcceptanceCriterionRepairRunState,
  ): PhaseOutcome {
    val run = context.run
    val output =
      FeatureTaskRuntimePhaseOutput(run.phaseId, context.loop.iteration, normalized.canonicalJson, normalized)
    context.loopTransitions.applyPersistedPhaseCompletion(output, phase.reviewPassNumber)
    if (scope.snapshot?.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED) {
      return PhaseOutcome.blocked(
        "The current acceptance criterion exhausted its three unsuccessful repair attempts. " +
          "Other criteria have separate budgets. " + scope.currentCriterionContext,
      )
    }
    return PhaseOutcome.completed(output)
  }
}
