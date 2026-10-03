package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.phaseLoopContext
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.observability.loopCapExhausted
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.slot.PhaseEntrySettlement
import skillbill.error.shellcontent.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeTransitionFunction

object FeatureTaskRuntimeRunLoopDrive {
  internal fun resumedReentry(context: FeatureTaskRuntimeRunLoopContext): PendingReentry? {
    val state = context.state
    val (loopId, reentry) = state.latestInFlightReentry ?: return null
    if (
      state.spanBlockedByEntryGate(reentry.span) ||
      FeatureTaskRuntimeRunLoopPlanningBranch.decideByLoop(context, loopId) { rules, stepState ->
        rules.discardsResumedReentry(loopId, stepState)
      } == true
    ) {
      context.runState.coupledRunTransitions.discardStaleResumedReentry(loopId)
      return null
    }
    context.runState.coupledRunTransitions.claimResumedInFlightEdge(loopId, reentry.edgeIteration)
    val resumePhaseId = reentry.resumePhaseId
    return PendingReentry(
      phaseId = resumePhaseId,
      loopId = loopId,
      edgeIteration = reentry.edgeIteration,
      drivingVerdict = reentry.drivingVerdict,
      expectedRepositoryCheckpoint =
        FeatureTaskRuntimeRunLoopPlanningBranch.decideByLoop(context, loopId) { rules, stepState ->
          rules.reentryCheckpoint(loopId, stepState)
        },
    )
  }

  internal fun reopenStaleSettledSteps(context: FeatureTaskRuntimeRunLoopContext) {
    FeatureTaskRuntimeRunLoopPlanningBranch.forEachSlotRules(context) { rules, stepState ->
      rules.reopenStaleSettledSteps(context.phaseLoopContext(), stepState)
    }
  }

  internal fun invalidateStaleEvidence(context: FeatureTaskRuntimeRunLoopContext) {
    FeatureTaskRuntimeRunLoopPlanningBranch.forEachSlotRules(context) { rules, stepState ->
      rules.invalidateStaleEvidence(context.phaseLoopContext(), stepState)
    }
  }

  internal fun settleWithoutLaunch(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): PhaseSettlement? =
    FeatureTaskRuntimeRunLoopPlanningBranch
      .decideByStep(context, phaseId) { rules, stepState ->
        rules.settleWithoutLaunch(phaseId, context.phaseLoopContext(), stepState)
      }?.let { settlement ->
        when (settlement) {
          is PhaseEntrySettlement.Completed -> PhaseSettlement.completed(phaseId, settlement.verdict)
          is PhaseEntrySettlement.Blocked -> {
            FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
              context.request,
              context.state,
              context.runState.coupledSession(),
              phaseId,
              settlement.reason,
            )
            PhaseSettlement.stop()
          }
        }
      }

  internal fun phaseEntryBlockReason(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? =
    entryGateBlockReason(context.state, context.transitions, phaseId)
      ?: with(FeatureTaskRuntimeRunLoopBackwardEdge) {
        FeatureTaskRuntimeRunLoopBackwardEdge.capExhaustedOnResume(
          context,
          phaseId,
        )
      }
      ?: FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(context, phaseId) { rules, stepState ->
        rules.entryBlockReason(phaseId, context.phaseLoopContext(), stepState)
      }

  internal fun entryGateBlockReason(
    state: FeatureTaskRuntimeRunState,
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    phaseId: String,
  ): String? {
    val settledVerdicts = state.settledVerdictsByPhaseId
    return transitions.entryGateViolation(phaseId, settledVerdicts)?.let { gate ->
      FeatureTaskRuntimePhaseOrderViolationError(
        phaseId = gate.phaseId,
        requiredPhaseId = gate.requiredPhaseId,
        requiredVerdict = gate.requiredVerdict.wireValue,
        observedVerdict = settledVerdicts[gate.requiredPhaseId]?.wireValue,
      ).message
    }
  }

  internal fun nextPhaseAfter(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): String? {
    val effectiveVerdict =
      FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(context, phaseId) { rules, stepState ->
        rules.routedVerdict(phaseId, verdict, stepState)
      } ?: verdict
    val edge = FeatureTaskRuntimeRunLoopCheckpoint.matchingBackwardEdge(context.transitions, phaseId, effectiveVerdict)
    edge?.let { FeatureTaskRuntimeRunLoopBackwardEdge.resumeInFlightReentry(context, it) }?.let { return it }
    val edgeIterationCount =
      edge?.let {
        FeatureTaskRuntimeRunLoopPlanningBranch.effectiveEdgeIterationCount(context.state, it)
      } ?: 0
    edge?.perEdgeCap?.takeIf { edgeIterationCount >= it }?.let { declaredCap ->
      context.observability.loopCapExhausted(phaseId, edge.loopId, declaredCap, effectiveVerdict)
    }
    val transition = resolveNextTransition(context, phaseId, effectiveVerdict, edgeIterationCount) ?: return null
    return with(FeatureTaskRuntimeRunLoopTransitions) {
      transitionTarget(
        context,
        phaseId,
        edge,
        effectiveVerdict,
        transition,
      )
    }
  }

  private fun traversal(context: FeatureTaskRuntimeRunLoopContext): FeatureTaskRuntimeTransitionDeclaration =
    context.runState.transitions

  private fun resolveNextTransition(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int,
  ): FeatureTaskRuntimeNextPhase? =
    runCatching {
      FeatureTaskRuntimeTransitionFunction.nextTransition(
        declaration = traversal(context),
        currentPhaseId = phaseId,
        verdict = verdict,
        edgeIterationCount = edgeIterationCount,
        context =
          FeatureTaskRuntimeTransitionContext(
            settledVerdictsByPhaseId = context.state.settledVerdictsByPhaseId,
          ),
      )
    }.getOrElse { error ->
      if (error !is FeatureTaskRuntimePhaseOrderViolationError) throw error
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
        context.request,
        context.state,
        context.runState.coupledSession(),
        error.phaseId,
        error.message.orEmpty(),
      )
      null
    }

  internal fun FeatureTaskRuntimeRunLoopContext.runPhaseDriveLoop(advance: (String) -> PhaseSettlement) {
    val explicitResume =
      request.goalContinuation
        ?.lastResumableStep
        ?.takeIf(String::isNotBlank)
        ?.let(state::explicitResumeStart)
    if (explicitResume != null) {
      runState.coupledRunTransitions.enterExplicitResumeStart(explicitResume)
    }
    var phaseId: String? =
      explicitResume?.phaseId
        ?: session.pendingReentry?.phaseId
        ?: traversal(this).forwardPhaseIds.first()
    phaseId =
      phaseId?.let { requested ->
        traversal(this).loopOnlySuccessors.entries.singleOrNull { (predecessor, successor) ->
          successor == requested && !state.phase(predecessor).completed
        }?.key ?: requested
      }
    while (phaseId != null) {
      val settled = advance(phaseId)
      val completedPhaseId = settled.completedPhaseId
      phaseId =
        if (completedPhaseId != null) {
          nextPhaseAfter(
            this,
            completedPhaseId,
            requireNotNull(settled.completedVerdict),
          )
        } else {
          null
        }
    }
  }

  internal fun advancePhaseReason(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? =
    if (context.state.phase(phaseId).completed) {
      context.state.phase(phaseId).output?.let { output ->
        FeatureTaskRuntimeRunLoopBackwardEdge.afterCompletion(context, output)
      }
    } else {
      with(FeatureTaskRuntimeRunLoopBackwardEdge) {
        FeatureTaskRuntimeRunLoopBackwardEdge.establishBranchIfNeeded(
          context = context,
          phaseId = phaseId,
        )
          ?: FeatureTaskRuntimeRunLoopBackwardEdge.runPhaseFor(
            context = context,
            phaseId = phaseId,
          )
      }
    }

  internal fun settleAdvanceOutcome(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    session: FeatureTaskRuntimeRunLoopSession,
    phaseId: String,
    reason: String?,
  ): PhaseSettlement =
    when {
      session.decomposed != null -> PhaseSettlement.stop()
      coupledRunTransitions(state, session).consumeRecordRejectionSettlementAdvance() != null -> {
        PhaseSettlement.completed(phaseId, FeatureTaskRuntimeVerdict.RECORD_REJECTED)
      }
      reason != null -> {
        if (session.paused == null) {
          FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(request, state, session, phaseId, reason)
        }
        PhaseSettlement.stop()
      }
      else -> PhaseSettlement.completed(phaseId, state.verdictFor(phaseId))
    }
}
