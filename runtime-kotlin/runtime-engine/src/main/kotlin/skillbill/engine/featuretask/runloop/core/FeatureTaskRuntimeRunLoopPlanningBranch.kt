package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeResumeLaunchAssignment
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.phaseLoopContext
import skillbill.engine.featuretask.runloop.finalization.FeatureTaskRuntimeRunLoopMonitorCycle
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeNonBlockingFailure
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPreLaunch
import skillbill.engine.featuretask.runloop.phase.PhaseFailureContinuationRequest
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.runloop.state.unresolvedReviewFindings
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseBoundLoopRules
import skillbill.engine.featuretask.slot.PhaseBoundStepHooks
import skillbill.engine.featuretask.slot.PhaseFailureClaimRules
import skillbill.engine.featuretask.slot.PhaseFailureContinuation
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseLoopCapability
import skillbill.engine.featuretask.slot.PhasePreparedRunCapability
import skillbill.engine.featuretask.slot.PhaseStepCapture
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepFailure
import skillbill.engine.featuretask.slot.PhaseStepFailureSource
import skillbill.engine.featuretask.slot.PhaseUpstreamFailureContinuationHooks
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeRunLoopPlanningBranch {
  internal fun blockOnCapExhaustion(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    transition: FeatureTaskRuntimeNextPhase.TerminalBlock,
  ) {
    val request = context.request
    val state = context.state
    val recorder = context.recorder
    val observability = context.observability
    val session = context.runState.coupledSession()
    val goalContinuationRecorder = context.goalContinuationRecorder
    val unresolvedFindings = state.unresolvedReviewFindings(phaseId)
    val reason =
      capExhaustionReason(
        CapExhaustionReasonArgs(
          request = request,
          recorder = recorder,
          loopId = transition.loopId,
          edgeIteration = transition.edgeIteration,
          verdict = transition.unresolvedVerdict,
          unresolvedFindings = unresolvedFindings,
          progress = state,
        ),
      )
    val run = capExhaustionPhaseRun(context, phaseId)
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
      context.runState,
      recorder,
      goalContinuationRecorder,
      BlockAndPersistArgs(
        run = run,
        attemptCount = state.phase(phaseId).nextIteration,
        reason = reason,
        observability = observability,
        loopId = transition.loopId,
        edgeIteration = transition.edgeIteration,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        payload = BlockAndPersistPayload(outputArtifact = state.phase(phaseId).output?.payload),
      ),
    )
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(request, state, session, phaseId, reason)
  }

  private fun capExhaustionPhaseRun(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): PhaseRun = buildPhaseRun(context, phaseId, context.request, context.specSource, null)

  internal fun runPhase(
    context: FeatureTaskRuntimeRunLoopContext,
    args: RunPhaseArgs,
  ): PhaseOutcome {
    val phaseId = args.phaseId
    val run =
      buildPhaseRun(
        context = context,
        phaseId = phaseId,
        request = args.request,
        specSource = args.specSource,
        reentry = args.reentry,
      )
    FeatureTaskRuntimeRunLoopPreLaunch
      .preLaunchBlock(
        context = context,
        run = run.copy(failureContinuation = StepBoundFailureContinuation(context)),
        state = args.state,
        observability = args.observability,
      )?.let { return it }
    return runPreparedPhase(context, run)
  }

  internal fun phaseDeclarationForRun(
    context: FeatureTaskRuntimeRunLoopContext,
    request: FeatureTaskRuntimeRunFacts,
    phaseId: String,
  ): FeatureTaskRuntimePhaseDeclaration =
    phaseDeclaration(
      phaseId,
      request.runInvariants.featureSize,
      context.runState.unselectedStepIds(),
    )

  internal fun buildPhaseRun(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    request: FeatureTaskRuntimeRunFacts,
    specSource: SpecSource,
    reentry: PendingReentry?,
  ): PhaseRun {
    val resolvedAgent =
      FeatureTaskRuntimeAgentResolver.resolve(
        phaseId = phaseId,
        assignment = request.agentAssignment,
        invokedAgentId = request.invokedAgentId,
      )
    val recorded = context.runState.stepLaunchAssignment(phaseId)
    val resolvedAgentId = resolvedAgent.resolvedAgentId
    return PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclarationForRun(context, request, phaseId),
      resolvedAgent = resolvedAgent,
      modelDirective =
        FeatureTaskRuntimeResumeLaunchAssignment.modelDirective(
          recorded,
          resolvedAgentId,
          phaseId,
          request.modelAssignment,
        ),
      compaction = request.compactionSettings.directiveFor(phaseId),
      request = request,
      specSource = specSource,
      policy = context.acceptedStepPolicy(phaseId),
      projectStoreRoot = context.projectStoreRoot,
      launchAssignment = FeatureTaskRuntimeResumeLaunchAssignment.recordedForAgent(recorded, resolvedAgentId),
      reentry = reentry,
      failureContinuationOutput =
        (context.runState.strategyFor(phaseId).stepHooks(phaseId) as? PhaseUpstreamFailureContinuationHooks)
          ?.failureContinuationOutput(context.state.outputs()),
    )
  }

  internal fun runPreparedPhase(
    context: FeatureTaskRuntimeRunLoopContext,
    run: PhaseRun,
  ): PhaseOutcome {
    val strategy = context.runState.strategyFor(run.phaseId)
    val dispatch = { prepared: PhaseRun -> dispatchPreparedPhase(context, prepared) }
    return (strategy as? PhasePreparedRunCapability)?.withPreparedRun(run, dispatch) ?: dispatch(run)
  }

  private fun dispatchPreparedPhase(
    context: FeatureTaskRuntimeRunLoopContext,
    run: PhaseRun,
  ): PhaseOutcome {
    val continuation = BoundFailureContinuation()
    val prepared = run.copy(failureContinuation = continuation)
    context.runState.stepBinding.authorizeCoordinatorDispatch(prepared)
    return context.runState
      .strategyFor(prepared.phaseId)
      .stepDescriptor(prepared.phaseId)
      .capture(PreparedPhaseDispatch(context, prepared, continuation))
  }

  internal fun <T : Any> decideByStep(
    context: FeatureTaskRuntimeRunLoopContext,
    stepId: String,
    decide: (PhaseBoundLoopRules) -> T?,
  ): T? =
    context.runState
      .strategyFor(stepId)
      .stepDescriptor(stepId)
      .capture(LoopRuleDispatch(context, stepId, decide))

  internal fun <T : Any> decideByLoop(
    context: FeatureTaskRuntimeRunLoopContext,
    loopId: String,
    decide: (PhaseBoundLoopRules) -> T?,
  ): T? =
    context.transitions.backwardEdges
      .firstOrNull { it.loopId == loopId }
      ?.let { edge ->
        decideByStep(context, context.runState.strategyFor(edge.destinationPhaseId).entryStep, decide)
      }

  internal fun claimsFailure(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): Boolean =
    (context.runState.strategyFor(phaseId) as? PhaseLoopCapability)
      ?.loopRules
      .let { it as? PhaseFailureClaimRules }
      ?.claimsFailure(phaseId, context.phaseLoopContext()) == true

  internal fun forEachSlotRules(
    context: FeatureTaskRuntimeRunLoopContext,
    act: (PhaseBoundLoopRules) -> Unit,
  ) {
    context.transitions.forwardPhaseIds.map(context.runState::strategyFor).distinct().forEach { strategy ->
      strategy
        .stepDescriptor(strategy.entryStep)
        .capture(LoopRuleDispatch(context, strategy.entryStep, act))
    }
  }

  internal fun effectiveEdgeIterationCount(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): Int =
    if (edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_LOOP_ID) {
      if (state.monitorFailureEvidenceUnavailable) {
        edge.perEdgeCap ?: state.monitorFailedRepairCommitShas.size
      } else {
        (state.monitorFailedRepairCommitShas.size - 1).coerceAtLeast(0)
      }
    } else {
      state.loop(edge.loopId).iteration
    }

  internal fun capExhaustionReason(args: CapExhaustionReasonArgs): String {
    val request = args.request
    val recorder = args.recorder
    val loopId = args.loopId
    val edgeIteration = args.edgeIteration
    val verdict = args.verdict
    val unresolvedFindings = args.unresolvedFindings
    if (loopId == FeatureTaskRuntimePhaseWorkflowDefinition.MONITOR_FIX_LOOP_ID) {
      return FeatureTaskRuntimeRunLoopMonitorCycle.monitorCapExhaustionReason(loopId, args.progress)
    }
    if (FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(loopId)) {
      val producer =
        FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER.entries
          .firstOrNull { it.value == loopId }
          ?.key
      val latest =
        producer?.let { producing ->
          recorder
            .loadQuarantinedRecords(request.workflowId)
            .orEmpty()
            .lastOrNull { it.producingPhaseId == producing }
        }
      val recordId = latest?.recordIdentifier() ?: producer?.let { "$it#<unknown-iteration>" } ?: "<unknown>"
      return "Quarantine-and-regenerate loop '$loopId' exhausted its regeneration cap after $edgeIteration " +
        "attempt(s): the quarantined record '$recordId' produced by phase '${producer ?: "<unknown>"}' still " +
        "fails projection validation. Retain the workflow and its evidence. Inspect status and diagnostics " +
        "with a compatible runtime or use a separately reviewed semantic mapping."
    }
    val findingsSuffix =
      if (unresolvedFindings.isEmpty()) {
        ""
      } else {
        " Unresolved findings: " +
          unresolvedFindings.joinToString("; ") { "[${it.severity.wireValue}] ${it.message}" } + "."
      }
    return "Backward-edge loop '$loopId' exhausted its per-edge cap after $edgeIteration iteration(s) with the " +
      "verdict '${verdict.wireValue}' still unresolved; the run blocks rather than re-entering past the cap." +
      findingsSuffix
  }

  private class LoopRuleDispatch<T>(
    private val context: FeatureTaskRuntimeRunLoopContext,
    private val stepId: String,
    private val decide: (PhaseBoundLoopRules) -> T?,
  ) : PhaseStepCapture<T?> {
    override fun <B : PhaseAcceptedStepExecution> capture(descriptor: PhaseStepDescriptor<B>): T? {
      val rules = descriptor.loopRules ?: return null
      val run =
        buildPhaseRun(
          context = context,
          phaseId = stepId,
          request = context.request,
          specSource = context.specSource,
          reentry = null,
        )
      context.runState.stepBinding.authorizeCoordinatorDispatch(run)
      val state = descriptor.bind(context.bindingFactory(run))
      return try {
        decide(PhaseBoundLoopRules.of(rules, state))
      } finally {
        state.finishStepExecution()
        context.runState.stepBinding.releaseCoordinatorDispatch()
      }
    }
  }

  private class BoundFailureContinuation : PhaseFailureContinuation {
    var hooks: PhaseBoundStepHooks<*>? = null

    override fun outputFor(
      run: PhaseRun,
      failure: PhaseStepFailure,
    ): PhaseFailureContinuationOutput? = hooks?.failureContinuationOutput(run, failure)
  }

  private class StepBoundFailureContinuation(
    private val context: FeatureTaskRuntimeRunLoopContext,
  ) : PhaseFailureContinuation {
    override fun outputFor(
      run: PhaseRun,
      failure: PhaseStepFailure,
    ): PhaseFailureContinuationOutput? =
      context.runState
        .strategyFor(run.phaseId)
        .stepDescriptor(run.phaseId)
        .capture(StopContinuationDispatch(context, run, failure))
  }

  private class StopContinuationDispatch(
    private val context: FeatureTaskRuntimeRunLoopContext,
    private val run: PhaseRun,
    private val failure: PhaseStepFailure,
  ) : PhaseStepCapture<PhaseFailureContinuationOutput?> {
    override fun <B : PhaseAcceptedStepExecution> capture(
      descriptor: PhaseStepDescriptor<B>,
    ): PhaseFailureContinuationOutput? {
      context.runState.stepBinding.authorizeCoordinatorDispatch(run)
      val state = descriptor.bind(context.bindingFactory(run))
      return try {
        state.boundHooks.failureContinuationOutput(run, failure)
      } finally {
        state.finishStepExecution()
        context.runState.stepBinding.releaseCoordinatorDispatch()
      }
    }
  }

  private class PreparedPhaseDispatch(
    private val context: FeatureTaskRuntimeRunLoopContext,
    private val run: PhaseRun,
    private val continuation: BoundFailureContinuation,
  ) : PhaseStepCapture<PhaseOutcome> {
    override fun <B : PhaseAcceptedStepExecution> capture(descriptor: PhaseStepDescriptor<B>): PhaseOutcome {
      val state = descriptor.bind(context.bindingFactory(run))
      continuation.hooks = state.boundHooks
      return try {
        execute(descriptor, state)
      } finally {
        state.finishStepExecution()
        context.runState.stepBinding.releaseCoordinatorDispatch()
      }
    }

    private fun <B : PhaseAcceptedStepExecution> execute(
      descriptor: PhaseStepDescriptor<B>,
      state: B,
    ): PhaseOutcome =
      try {
        descriptor.execute(run, state)
      } catch (error: SkillBillRuntimeException) {
        FeatureTaskRuntimeNonBlockingFailure.settle(
          context.recorder,
          context.runState.coupledRunTransitions,
          context.observability,
          PhaseFailureContinuationRequest(
            run = run,
            attempt = context.state.phase(run.phaseId).nextIteration,
            reason = "Phase execution could not finish: ${error.code}",
            source = PhaseStepFailureSource.THROWN,
          ),
        ) ?: throw error
      }
  }
}

internal fun remediationCheckpointBlockedReason(
  branch: String,
  error: String,
): String =
  "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
    "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."
