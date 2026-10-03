package skillbill.engine.featuretask.runloop.core

import skillbill.application.decomposition.specSource
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPreLaunch
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.runloop.state.unresolvedReviewFindings
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
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
  ): PhaseRun {
    val request = context.request
    val resolvedAgent =
      FeatureTaskRuntimeAgentResolver.resolve(
        phaseId = phaseId,
        assignment = request.agentAssignment,
        invokedAgentId = request.invokedAgentId,
      )
    return PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclarationForRun(context, request, phaseId),
      resolvedAgent = resolvedAgent,
      modelDirective =
        FeatureTaskRuntimeModelResolver.resolve(
          phaseId,
          resolvedAgent.resolvedAgentId,
          request.modelAssignment,
        ),
      compaction = request.compactionSettings.directiveFor(phaseId),
      request = request,
      specSource = context.specSource,
      policy = context.acceptedStepPolicy(phaseId),
    )
  }

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
        run = run,
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
    return PhaseRun(
      phaseId = phaseId,
      declaration = phaseDeclarationForRun(context, request, phaseId),
      resolvedAgent = resolvedAgent,
      modelDirective =
        FeatureTaskRuntimeModelResolver.resolve(
          phaseId,
          resolvedAgent.resolvedAgentId,
          request.modelAssignment,
        ),
      compaction = request.compactionSettings.directiveFor(phaseId),
      request = request,
      specSource = specSource,
      policy = context.acceptedStepPolicy(phaseId),
      reentry = reentry,
    )
  }

  internal fun runPreparedPhase(
    context: FeatureTaskRuntimeRunLoopContext,
    run: PhaseRun,
  ): PhaseOutcome {
    context.runState.stepBinding.authorizeCoordinatorDispatch(run)
    val state = context.acceptedStep(run)
    return try {
      context.runState.strategyFor(run.phaseId).runStep(run, state)
    } finally {
      state.finishStepExecution()
      context.runState.stepBinding.releaseCoordinatorDispatch()
    }
  }

  internal fun <T : Any> decideByStep(
    context: FeatureTaskRuntimeRunLoopContext,
    stepId: String,
    decide: (PhaseLoopRules, PhaseAcceptedStepExecution) -> T?,
  ): T? =
    withLoopRuleBinding(context, stepId) { state ->
      context.runState
        .strategyFor(stepId)
        .loopRules
        ?.let { rules -> decide(rules, state) }
    }

  internal fun <T : Any> decideByLoop(
    context: FeatureTaskRuntimeRunLoopContext,
    loopId: String,
    decide: (PhaseLoopRules, PhaseAcceptedStepExecution) -> T?,
  ): T? =
    context.transitions.backwardEdges
      .firstOrNull { it.loopId == loopId }
      ?.let { edge -> decideByStep(context, edge.destinationPhaseId, decide) }

  internal fun forEachSlotRules(
    context: FeatureTaskRuntimeRunLoopContext,
    act: (PhaseLoopRules, PhaseAcceptedStepExecution) -> Unit,
  ) {
    context.transitions.forwardPhaseIds.map(context.runState::strategyFor).distinct().forEach { strategy ->
      strategy.loopRules?.let { rules ->
        withLoopRuleBinding(context, strategy.entryStep) { state -> act(rules, state) }
      }
    }
  }

  private fun <T> withLoopRuleBinding(
    context: FeatureTaskRuntimeRunLoopContext,
    stepId: String,
    use: (PhaseAcceptedStepExecution) -> T,
  ): T {
    val run =
      buildPhaseRun(
        context = context,
        phaseId = stepId,
        request = context.request,
        specSource = context.specSource,
        reentry = null,
      )
    context.runState.stepBinding.authorizeCoordinatorDispatch(run)
    val state = context.acceptedStep(run)
    return try {
      use(state)
    } finally {
      state.finishStepExecution()
      context.runState.stepBinding.releaseCoordinatorDispatch()
    }
  }

  internal fun effectiveEdgeIterationCount(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): Int = state.loop(edge.loopId).iteration

  internal fun capExhaustionReason(args: CapExhaustionReasonArgs): String {
    val request = args.request
    val recorder = args.recorder
    val loopId = args.loopId
    val edgeIteration = args.edgeIteration
    val verdict = args.verdict
    val unresolvedFindings = args.unresolvedFindings
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
}

internal fun remediationCheckpointBlockedReason(
  branch: String,
  error: String,
): String =
  "Feature-task-runtime could not establish a remediation checkpoint on the feature branch '$branch' " +
    "before re-entering a mutating phase" + (if (error.isBlank()) "." else " ($error).") +
    " Refusing to re-enter a mutating phase on a dirty, non-reconcilable tree."
