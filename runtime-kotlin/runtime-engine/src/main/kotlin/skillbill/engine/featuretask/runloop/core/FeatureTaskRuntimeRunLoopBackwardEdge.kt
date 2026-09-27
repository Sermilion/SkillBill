package skillbill.engine.featuretask.runloop.core

import skillbill.application.decomposition.specSource
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeRunLoopBackwardEdge {
  internal fun resumeInFlightReentry(
    context: FeatureTaskRuntimeRunLoopContext,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): String? {
    val state = context.state
    val resumes =
      FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(context, edge.destinationPhaseId) { rules, _ ->
        rules.resumesInFlightReentry(edge.loopId)
      } == true
    if (!resumes || state.isLoopLiveClaimed(edge.loopId) || state.isComplete(edge.destinationPhaseId)) {
      return null
    }
    val destinationRecord =
      state.recordFor(edge.destinationPhaseId)
        ?.takeIf { it.loopId == edge.loopId && it.edgeIteration == state.edgeIterationCount(edge.loopId) }
        ?: return null
    val edgeIteration = requireNotNull(destinationRecord.edgeIteration)
    state.reopenForReentry(edge.fromPhaseId)
    state.recordEdgeIteration(edge.loopId, edgeIteration)
    val pendingReentry =
      PendingReentry(
        phaseId = edge.destinationPhaseId,
        loopId = edge.loopId,
        edgeIteration = edgeIteration,
        drivingVerdict = edge.triggeringVerdict,
        expectedRepositoryCheckpoint = reentryCheckpoint(context, edge),
      )
    context.session.transitionReentryPair(pendingReentry, pendingReentry)
    return edge.destinationPhaseId
  }

  private fun reentryCheckpoint(
    context: FeatureTaskRuntimeRunLoopContext,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): String? =
    FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(context, edge.destinationPhaseId) { rules, stepState ->
      rules.reentryCheckpoint(edge.loopId, stepState)
    }

  internal fun recordBackwardEdge(
    context: FeatureTaskRuntimeRunLoopContext,
    session: FeatureTaskRuntimeRunLoopSession,
    edge: FeatureTaskRuntimeBackwardEdge,
    edgeIteration: Int,
    verdict: FeatureTaskRuntimeVerdict,
  ) = with(context) {
    val destinationPhaseId = edge.destinationPhaseId
    val loopId = edge.loopId
    val reopenedSpan =
      spanBetween(
        transitions,
        destinationPhaseId,
        edge.fromPhaseId,
      )
    reopenedSpan.forEach(state::reopenForReentry)
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      state.invalidateProducerOutput(destinationPhaseId)
      recorder.invalidateQuarantinedProducerRecord(
        request.workflowId,
        destinationPhaseId,
        loopId,
        edgeIteration,
      )
    }
    state.recordEdgeIteration(loopId, edgeIteration)
    val pendingReentry =
      PendingReentry(
        phaseId = destinationPhaseId,
        loopId = loopId,
        edgeIteration = edgeIteration,
        drivingVerdict = verdict,
        expectedRepositoryCheckpoint = reentryCheckpoint(context, edge),
      )
    session.transitionReentryPair(pendingReentry, pendingReentry)
  }

  internal fun warnOnThresholdCrossing(
    request: FeatureTaskRuntimeRunFacts,
    diagnostics: RuntimeDiagnostics,
    edge: FeatureTaskRuntimeBackwardEdge,
    edgeIteration: Int,
  ) {
    val threshold = edge.warnAfterIterations ?: return
    if (edgeIteration != threshold + 1) return
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      thresholdCrossingWarning(request, edge.loopId, threshold, edgeIteration),
    )
  }

  internal fun thresholdCrossingWarning(
    request: FeatureTaskRuntimeRunFacts,
    loopId: String,
    threshold: Int,
    edgeIteration: Int,
  ): String =
    "Remediation loop '$loopId' exceeded its warning threshold of $threshold: entering iteration " +
      "$edgeIteration for issue ${request.issueKey}, workflow ${request.workflowId}, subtask " +
      "${request.goalContinuation?.subtaskId ?: request.issueKey}, spec " +
      "${request.runInvariants.specReference}."

  internal fun capExhaustedOnResume(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? =
    with(context) {
      if (FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(session, phaseId)) return null
      val record = state.recordFor(phaseId) ?: return null
      return FeatureTaskRuntimeRunLoopBackwardEdge.capExhaustionForRecord(
        context,
        phaseId,
        record,
      )
    }

  internal fun capExhaustionForRecord(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    record: FeatureTaskRuntimePhaseRecord,
  ): String? =
    with(context) {
      val loopId = record.loopId
      val iteration = record.edgeIteration
      if (loopId == null || iteration == null || state.isLoopLiveClaimed(loopId)) {
        return null
      }
      val edge =
        transitions.backwardEdges.firstOrNull { candidate ->
          candidate.loopId == loopId &&
            (candidate.destinationPhaseId == phaseId || candidate.fromPhaseId == phaseId)
        }
      if (edge?.destinationPhaseId == phaseId) {
        val sourceRecord = state.recordFor(edge.fromPhaseId)
        if (
          sourceRecord?.status?.workflowStepStatus() == WorkflowStepStatus.BLOCKED && sourceRecord.loopId == loopId &&
          sourceRecord.edgeIteration == iteration
        ) {
          return null
        }
      }
      return edge
        ?.takeIf { candidate -> blocksWhenCapExhausted(candidate, iteration) }
        ?.let {
          FeatureTaskRuntimeRunLoopPlanningBranch.capExhaustionReason(
            CapExhaustionReasonArgs(
              request = request,
              recorder = recorder,
              loopId = loopId,
              edgeIteration = iteration,
              verdict = it.triggeringVerdict,
              unresolvedFindings = emptyList<FeatureTaskRuntimeReviewFinding>(),
            ),
          )
        }
    }

  internal fun blocksWhenCapExhausted(
    edge: FeatureTaskRuntimeBackwardEdge,
    iteration: Int,
  ): Boolean =
    edge.capExhaustionBehavior == FeatureTaskRuntimeCapExhaustionBehavior.BLOCK &&
      edge.perEdgeCap?.let { iteration >= it } == true

  internal fun runPhaseFor(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? =
    with(context) {
      val briefingReentry = session.pendingReentry?.takeIf { it.phaseId == phaseId }
      if (briefingReentry != null) session.transitionPendingReentry(null)
      val reentry =
        briefingReentry ?: session.activeReentry?.takeIf { active ->
          transitions.backwardEdges
            .firstOrNull { it.loopId == active.loopId }
            ?.let { edge ->
              phaseId in
                spanBetween(
                  transitions,
                  edge.destinationPhaseId,
                  edge.fromPhaseId,
                )
            } == true
        }?.copy(phaseId = phaseId)
      val outcome =
        FeatureTaskRuntimeRunLoopPlanningBranch.runPhase(
          context,
          RunPhaseArgs(
            phaseId = phaseId,
            request = request,
            state = state,
            observability = observability,
            specSource = specSource,
            reentry = reentry,
          ),
        )
      outcome.regenerationTargetPhaseId?.let {
        session.markRecordRejectionSettlementPending()
        return null
      }
      outcome.pausedReason?.let { return it }
      return outcome.blockedReason ?: run {
        val completedOutput = requireNotNull(outcome.completedOutput)
        state.recordCompleted(completedOutput)
        session.consumeOperatorBlockRetryCompletion(phaseId)
        afterCompletion(context, completedOutput)
      }
    }

  internal fun afterCompletion(
    context: FeatureTaskRuntimeRunLoopContext,
    output: FeatureTaskRuntimePhaseOutput,
  ): String? = context.strategyFor(output.phaseId).stepHooks(output.phaseId).afterCompletion(context, output)

  internal fun establishBranchIfNeeded(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? =
    with(context) {
      if (!stepPolicy(phaseId).fileMutating) {
        return null
      }
      val setup =
        runState.ensureFeatureBranch(
          guardPhase =
            strategies.selectedStrategies(strategySelectionFacts(request))
              .firstOrNull { strategy -> strategy.slot == PhaseSlot.IMPLEMENTATION }
              ?.entryStep
              ?: phaseId,
        )
      return setup.blockedReason?.also { reason ->
        FeatureTaskRuntimeRunLoopPhaseBlocking.persistBranchSetupBlock(
          request,
          recorder,
          observability,
          phaseId,
          reason,
        )
      } ?: run {
        setup.establishedBranch?.let(session::transitionResolvedBranch)
        FeatureTaskRuntimeRunLoopPhaseBlocking.clearRecoveredBranchSetupBlock(state, phaseId)
        null
      }
    }
}
