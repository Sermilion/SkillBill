package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.phaseLoopContext
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.observability.loopCapExhausted
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopAcceptanceRepair
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopContainment
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.slot.PhaseEntrySettlement
import skillbill.error.core.catchingNonCancellation
import skillbill.error.shellcontent.featureTaskRuntimePhaseOrderViolationMessage
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionResult
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeTransitionFunction

internal object FeatureTaskRuntimeRunLoopDrive {
  internal fun resumedReentry(context: FeatureTaskRuntimeRunLoopContext): PendingReentry? {
    val state = context.state
    val (loopId, reentry) = state.latestInFlightReentry ?: return null
    val gateAdmissionSpan =
      if (state.isValidatedCompletedReturn(loopId, reentry)) listOf(reentry.resumePhaseId) else reentry.span
    if (
      state.spanBlockedByEntryGate(gateAdmissionSpan) ||
      FeatureTaskRuntimeRunLoopPlanningBranch.decideByLoop(context, loopId) { rules ->
        rules.discardsResumedReentry(loopId)
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
      completionDerived = reentry.completionDerived,
      resumeCoveredPhaseIds = reentry.resumeCoveredPhaseIds,
      expectedRepositoryCheckpoint =
        FeatureTaskRuntimeRunLoopPlanningBranch.decideByLoop(context, loopId) { rules ->
          rules.reentryCheckpoint(loopId)
        },
    )
  }

  internal fun reopenStaleSettledSteps(context: FeatureTaskRuntimeRunLoopContext) {
    FeatureTaskRuntimeRunLoopPlanningBranch.forEachSlotRules(context) { rules ->
      if (!context.hasLeftSlot(rules.acceptedOwner.slot)) {
        rules.reopenStaleSettledSteps(context.phaseLoopContext())
      }
    }
  }

  internal fun invalidateStaleEvidence(context: FeatureTaskRuntimeRunLoopContext) {
    FeatureTaskRuntimeRunLoopPlanningBranch.forEachSlotRules(context) { rules ->
      if (!context.hasLeftSlot(rules.acceptedOwner.slot)) {
        rules.invalidateStaleEvidence(context.phaseLoopContext())
      }
    }
  }

  internal fun settleWithoutLaunch(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): PhaseSettlement? =
    crossSlotSettlement(context, phaseId)?.let { verdict -> PhaseSettlement.completed(phaseId, verdict) }
      ?: ownSettlement(context, phaseId)

  private fun crossSlotSettlement(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): FeatureTaskRuntimeVerdict? {
    val owner = context.runState.selectedOwnerOf(phaseId)?.slot
    var verdict: FeatureTaskRuntimeVerdict? = null
    FeatureTaskRuntimeRunLoopPlanningBranch.forEachSlotRules(context) { rules ->
      if (verdict == null && rules.acceptedOwner.slot != owner) {
        verdict = rules.crossSlotSettlement(phaseId, context.phaseLoopContext())
      }
    }
    return verdict
  }

  private fun ownSettlement(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): PhaseSettlement? =
    FeatureTaskRuntimeRunLoopPlanningBranch
      .decideByStep(context, phaseId) { rules ->
        rules.settleWithoutLaunch(phaseId, context.phaseLoopContext())
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
    FeatureTaskRuntimeRunLoopAcceptanceRepair.reviewEntryBlockReason(context, phaseId)
      ?: (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair?.let { scope ->
        if (scope.snapshot?.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED) {
          "The current acceptance criterion exhausted its three unsuccessful repair attempts. " +
            "Other criteria have separate budgets. ${scope.currentCriterionContext}"
        } else {
          null
        }
      } ?: entryGateBlockReason(context.state, context.transitions, phaseId)
      ?: with(FeatureTaskRuntimeRunLoopBackwardEdge) {
        FeatureTaskRuntimeRunLoopBackwardEdge.capExhaustedOnResume(
          context,
          phaseId,
        )
      }
      ?: FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(context, phaseId) { rules ->
        rules.entryBlockReason(phaseId, context.phaseLoopContext())
      }

  internal fun entryGateBlockReason(
    state: FeatureTaskRuntimeRunState,
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    phaseId: String,
  ): String? {
    val settledVerdicts = state.settledVerdictsByPhaseId
    return transitions.entryGateViolation(phaseId, settledVerdicts)?.let { gate ->
      featureTaskRuntimePhaseOrderViolationMessage(
        phaseId = gate.phaseId,
        requiredPhaseId = gate.requiredPhaseId,
        requiredVerdict = gate.requiredVerdict.wireValue,
        observedVerdict = settledVerdicts[gate.requiredPhaseId]?.wireValue,
      )
    }
  }

  internal fun nextPhaseAfter(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): String? {
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair
    val serial = scope?.snapshot
    if (serial != null && FeatureTaskRuntimeRunLoopAcceptanceRepair.ownsCompletedPhase(phaseId, scope.steps)) {
      return when (val routing = FeatureTaskRuntimeRunLoopAcceptanceRepair.next(context, phaseId)) {
        is FeatureTaskRuntimeRunLoopAcceptanceRepair.Routing.Reenter -> routing.phaseId
        FeatureTaskRuntimeRunLoopAcceptanceRepair.Routing.Stop -> null
        FeatureTaskRuntimeRunLoopAcceptanceRepair.Routing.Continue -> nextTransitionAfter(context, phaseId, verdict)
      }
    }
    return nextTransitionAfter(context, phaseId, verdict)
  }

  private fun nextTransitionAfter(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): String? {
    val effectiveVerdict =
      FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(context, phaseId) { rules ->
        rules.routedVerdict(phaseId, verdict)
      } ?: verdict
    val edge = FeatureTaskRuntimeRunLoopCheckpoint.matchingBackwardEdge(traversal(context), phaseId, effectiveVerdict)
    edge?.let { FeatureTaskRuntimeRunLoopBackwardEdge.resumeInFlightReentry(context, it) }?.let { return it }
    val edgeIterationCount =
      edge?.let {
        FeatureTaskRuntimeRunLoopPlanningBranch.effectiveEdgeIterationCount(context.state, it)
      } ?: 0
    edge?.perEdgeCap?.takeIf { edgeIterationCount >= it }?.let { declaredCap ->
      context.observability.loopCapExhausted(phaseId, edge.loopId, declaredCap, effectiveVerdict)
      FeatureTaskRuntimeRunLoopPlanningBranch.forEachSlotRules(context) { rules ->
        rules.onLoopCapExhausted(edge.loopId, declaredCap, context.phaseLoopContext())
      }
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
    when (
      val result =
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
    ) {
      is FeatureTaskRuntimeTransitionResult.Resolved -> result.next
      is FeatureTaskRuntimeTransitionResult.PhaseOrderViolation -> {
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
          context.request,
          context.state,
          context.runState.coupledSession(),
          result.phaseId,
          result.message,
        )
        null
      }
    }

  internal fun FeatureTaskRuntimeRunLoopContext.runPhaseDriveLoop(advance: (String) -> PhaseSettlement) {
    var phaseId: String? =
      driveStartPhaseId().let { requested ->
        traversal(this).loopOnlySuccessors.entries.singleOrNull { (predecessor, successor) ->
          successor == requested && !state.phase(predecessor).completed
        }?.key ?: requested
      }
    while (phaseId != null) {
      phaseId = driveStep(phaseId, advance)
    }
  }

  private fun FeatureTaskRuntimeRunLoopContext.driveStep(
    phaseId: String,
    advance: (String) -> PhaseSettlement,
  ): String? {
    val route = { completed: String, verdict: FeatureTaskRuntimeVerdict -> nextPhaseAfter(this, completed, verdict) }
    val settled =
      catchingNonCancellation { advance(phaseId) }.getOrElse { failure ->
        return FeatureTaskRuntimeRunLoopContainment.containedNextPhase(
          this,
          phaseId,
          failure.containableOrThrow(),
          route,
        )
      }
    val completedPhaseId = settled.completedPhaseId ?: return null
    return catchingNonCancellation { route(completedPhaseId, requireNotNull(settled.completedVerdict)) }
      .getOrElse { failure ->
        FeatureTaskRuntimeRunLoopContainment.containedNextPhase(
          this,
          completedPhaseId,
          failure.containableOrThrow(),
          route,
        )
      }
  }

  private fun FeatureTaskRuntimeRunLoopContext.driveStartPhaseId(): String {
    val explicitResume =
      request.goalContinuation
        ?.lastResumableStep
        ?.takeIf(String::isNotBlank)
        ?.let(state::explicitResumeStart)
    val acceptanceResume = FeatureTaskRuntimeRunLoopAcceptanceRepair.resume(this)
    val recoveredReturnOverridesExplicit =
      explicitResume != null && session.pendingReentry?.let { reentry ->
        reentry.completionDerived && explicitResume.phaseId in reentry.resumeCoveredPhaseIds
      } == true
    if (explicitResume != null && acceptanceResume == null && !recoveredReturnOverridesExplicit) {
      runState.coupledRunTransitions.enterExplicitResumeStart(explicitResume)
    }
    return acceptanceResume
      ?: (if (recoveredReturnOverridesExplicit) session.pendingReentry?.phaseId else explicitResume?.phaseId)
      ?: session.pendingReentry?.phaseId
      ?: traversal(this).forwardPhaseIds.first()
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
