package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.runloop.core.FixLoopBranchContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseAttemptLoopState
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.phaseAttemptAccumulatorContext
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.observability.featureTaskRuntimeStartContinuationKind
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import java.time.Clock

/** Runs the attempts of one step call and settles the step, so a run state decides how its steps launch. */
internal fun interface PhaseStepAttempts {
  /** Runs the attempts [call] makes for [run] and returns the step's outcome. */
  fun run(run: PhaseRun, call: PhaseStepCall): PhaseOutcome
}

internal data class PhaseAttemptCollaborators(
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val clock: Clock,
  val diagnostics: RuntimeDiagnostics,
)

internal object PhaseAttemptLoop : PhaseStepAttempts {
  override fun run(run: PhaseRun, call: PhaseStepCall): PhaseOutcome = with(PhaseAttemptSteps) {
    PhaseAttemptScope(run.request, call.state).runPhaseAttempts(run, call)
  }
}

internal object PhaseAttemptSteps {
  fun PhaseAttemptEnvironment.runPhaseAttempts(run: PhaseRun, call: PhaseStepCall): PhaseOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var iteration = state.nextIteration(run.phaseId)
    val continuationSegmentCount =
      FeatureTaskRuntimeRunLoopPhaseBlocking
        .durableContinuationSegmentCount(recorder, run)
    val nonOutputAttempts = FeatureTaskRuntimeRunLoopPhaseBlocking.durableNonOutputAttempts(state, run)
    try {
      PhaseAttemptOnce.persistRequiredStart(this, run, iteration)
    } catch (rejection: RequiredPhaseWriteRejected) {
      return PhaseAttemptOnce.blockRequiredWriteRejection(this, run, rejection)
    }
    if (FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(session, run.phaseId)) {
      state.restartAttemptBudget(run.phaseId)
    }
    val semanticIteration =
      (
        state.fixLoopIterationFor(run.phaseId, iteration) - continuationSegmentCount - nonOutputAttempts.size
        ).coerceAtLeast(1)
    val crashResumed = state.resumedFromPriorProcess(run.phaseId)
    state.recordPhaseLaunched(run.phaseId)
    stepHooks(run).onLaunch(run, this)
    observability.started(
      run.phaseId,
      agentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry(
        resumed = iteration > 1 || state.hasPriorRecord(run.phaseId),
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
        malformedAttemptCount = 0,
        outputGateFailures = 0,
        semanticIteration = semanticIteration,
        continuationSegmentCount = continuationSegmentCount,
      )
    while (outcome == null) {
      outcome =
        resolveFixLoopOutcome(
          FixLoopOutcomeArgs(
            context =
            phaseAttemptAccumulatorContext(
              run,
              state,
              loop.iteration,
              observability,
            ),
            loop = loop,
            agentId = agentId,
            call = call,
          ),
        )
    }
    return outcome
  }

  fun PhaseAttemptEnvironment.resolveFixLoopOutcome(args: FixLoopOutcomeArgs): PhaseOutcome? {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val observability = args.context.attempt.observability
    val loop = args.loop
    val agentId = args.agentId
    val attempt =
      PhaseAttemptOnce.attemptOnce(
        this@resolveFixLoopOutcome,
        recordRejectionAttemptArgs(
          PhaseAttemptContext(run, state, loop.iteration, observability, loop.outputGateFailures),
          args.call,
          priorCorrection = loop.priorCorrection,
        ),
      )
    val context = FixLoopBranchContext(run, attempt, loop, observability, agentId)
    val phaseAttempts = PhaseAttemptContinuations
    return attempt.settledOutcome ?: when {
      attempt.incompleteWorkContinuationReason != null ->
        phaseAttempts.settleIncompleteWork(
          request,
          state,
          recorder,
          observability,
          context,
        )
      attempt.boundaryBodyDeliveryContinuationReason != null ->
        phaseAttempts.settleBoundaryBodyDelivery(observability, context)
      attempt.malformedOutput -> phaseAttempts.settleMalformedOutput(request, state, recorder, observability, context)
      attempt.retryableTerminalRetryReason != null ->
        phaseAttempts.settleRetryableTerminal(
          request,
          state,
          recorder,
          observability,
          context,
        )
      else ->
        FeatureTaskRuntimeRunLoopPhaseBlocking.settleSemanticFailure(
          request,
          state,
          recorder,
          observability,
          context,
        )
    }
  }
}
