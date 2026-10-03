package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.launchHookContext
import skillbill.engine.featuretask.runloop.attempt.phaseAttemptContext
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.FixLoopBranchContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptAccumulatorContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptLoopState
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.observability.featureTaskRuntimeStartContinuationKind
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite

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
        .durableContinuationSegmentCount(recorder, run)
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
      )
    val context =
      FixLoopBranchContext(
        run,
        attempt,
        loop,
        observability,
        agentId,
        coupling.session,
        state,
        coupling.transitions,
      )
    val phaseAttempts = PhaseAttemptContinuations
    return attempt.settledOutcome ?: when {
      attempt.incompleteWorkContinuationReason != null ->
        phaseAttempts.settleIncompleteWork(
          recorder,
          context,
        )
      attempt.boundaryBodyDeliveryContinuationReason != null ->
        phaseAttempts.settleBoundaryBodyDelivery(observability, context)
      attempt.retryableTerminal != null ->
        phaseAttempts.settleRetryableTerminal(recorder, context, requireNotNull(attempt.retryableTerminal))
      else ->
        FeatureTaskRuntimeRunLoopPhaseBlocking.settleSemanticFailure(recorder, context)
    }
  }
}
