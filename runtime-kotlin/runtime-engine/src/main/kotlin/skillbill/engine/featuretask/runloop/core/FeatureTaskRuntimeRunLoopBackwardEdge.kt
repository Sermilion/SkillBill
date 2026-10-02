package skillbill.engine.featuretask.runloop.core

import skillbill.application.decomposition.specSource
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.traversalHookContext
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking.persistBranchSetupBlock
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.error.featuretask.FeatureTaskRuntimeRegenerationRefusal
import skillbill.error.featuretask.UnsafeFeatureTaskRuntimeRegenerationError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewFinding
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
    if (!resumes || state.loop(edge.loopId).liveClaimed || state.phase(edge.destinationPhaseId).completed) {
      return null
    }
    val destinationRecord =
      state
        .phase(edge.destinationPhaseId).record
        ?.takeIf { it.loopId == edge.loopId && it.edgeIteration == state.loop(edge.loopId).iteration }
        ?: return null
    val edgeIteration = requireNotNull(destinationRecord.edgeIteration)
    val pendingReentry =
      PendingReentry(
        phaseId = edge.destinationPhaseId,
        loopId = edge.loopId,
        edgeIteration = edgeIteration,
        drivingVerdict = edge.triggeringVerdict,
        expectedRepositoryCheckpoint = reentryCheckpoint(context, edge),
      )
    context.runState.coupledRunTransitions.resumeInFlightReentry(
      fromPhaseId = edge.fromPhaseId,
      loopId = edge.loopId,
      edgeIteration = edgeIteration,
      pendingReentry = pendingReentry,
    )
    return edge.destinationPhaseId
  }

  private fun reentryCheckpoint(
    context: FeatureTaskRuntimeRunLoopContext,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): String? =
    FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(
      context,
      context.runState.strategyFor(edge.destinationPhaseId).entryStep,
    ) { rules, stepState -> rules.reentryCheckpoint(edge.loopId, stepState) }

  internal fun recordBackwardEdge(
    context: FeatureTaskRuntimeRunLoopContext,
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
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      val invalidated =
        recorder.invalidateQuarantinedProducerRecord(
          request.workflowId,
          destinationPhaseId,
          loopId,
          edgeIteration,
        )
      if (!invalidated) {
        throw UnsafeFeatureTaskRuntimeRegenerationError(FeatureTaskRuntimeRegenerationRefusal.MISSING_WORKFLOW)
      }
      context.runState.coupledRunTransitions.invalidateProducerOutputForRegeneration(destinationPhaseId)
    }
    val pendingReentry =
      PendingReentry(
        phaseId = destinationPhaseId,
        loopId = loopId,
        edgeIteration = edgeIteration,
        drivingVerdict = verdict,
        expectedRepositoryCheckpoint = reentryCheckpoint(context, edge),
      )
    context.runState.coupledRunTransitions.enterBackwardEdgeReentry(
      reopenedPhaseIds = reopenedSpan,
      loopId = loopId,
      edgeIteration = edgeIteration,
      pendingReentry = pendingReentry,
    )
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
      val record = state.phase(phaseId).record ?: return null
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
      if (loopId == null || iteration == null || state.loop(loopId).liveClaimed) {
        return null
      }
      val edge =
        transitions.backwardEdges.firstOrNull { candidate ->
          candidate.loopId == loopId &&
            (candidate.destinationPhaseId == phaseId || candidate.fromPhaseId == phaseId)
        }
      if (edge?.destinationPhaseId == phaseId) {
        val sourceRecord = state.phase(edge.fromPhaseId).record
        if (
          sourceRecord?.status?.workflowStepStatus() == WorkflowStepStatus.BLOCKED &&
          sourceRecord.loopId == loopId &&
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
      val briefingReentry = runState.coupledRunTransitions.consumeBriefingPendingReentry(phaseId)
      val reentry =
        briefingReentry ?: session.activeReentry
          ?.takeIf { active ->
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
        runState.coupledRunTransitions.markRecordRejectionSettlementPending()
        return null
      }
      outcome.pausedReason?.let { return it }
      return outcome.blockedReason ?: run {
        val completedOutput = requireNotNull(outcome.completedOutput)
        runState.coupledRunTransitions.recordForwardPhaseCompletion(completedOutput, phaseId)
        afterCompletion(context, completedOutput)
      }
    }

  internal fun afterCompletion(
    context: FeatureTaskRuntimeRunLoopContext,
    output: FeatureTaskRuntimePhaseOutput,
  ): String? =
    context.runState
      .strategyFor(
        output.phaseId,
      ).stepHooks(output.phaseId)
      .afterCompletion(
        context.traversalHookContext(output, context.runState.strategyFor(output.phaseId).stepHooks(output.phaseId)),
        output,
      )

  internal fun establishBranchIfNeeded(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? =
    with(context) {
      if (!context.acceptedStepPolicy(phaseId).fileMutating) {
        return null
      }
      val setup =
        runState.ensureFeatureBranch(
          guardPhase =
            transitions.forwardPhaseIds.firstOrNull { context.acceptedStepPolicy(it).fileMutating } ?: phaseId,
        )
      return setup.blockedReason?.also { reason ->
        runState.coupledRunTransitions.persistBranchSetupBlock(
          request,
          recorder,
          observability,
          phaseId,
          reason,
        )
      } ?: run {
        setup.establishedBranch?.let(runState.coupledRunTransitions::observeResolvedBranchForCheckpoint)
        FeatureTaskRuntimeRunLoopPhaseBlocking.clearRecoveredBranchSetupBlock(runState.coupledRunTransitions, phaseId)
        null
      }
    }
}
