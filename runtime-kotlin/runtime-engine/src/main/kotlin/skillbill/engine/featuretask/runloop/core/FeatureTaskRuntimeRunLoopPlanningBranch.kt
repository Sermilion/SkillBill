package skillbill.engine.featuretask.runloop.core

import skillbill.application.decomposition.specSource
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeModelResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPreLaunch
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopSkeletonPhaseRunState
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseDeclaration
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
    val session = context.session
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
      request,
      state,
      recorder,
      goalContinuationRecorder,
      BlockAndPersistArgs(
        run = run,
        attemptCount = state.nextIteration(phaseId),
        reason = reason,
        observability = observability,
        loopId = transition.loopId,
        edgeIteration = transition.edgeIteration,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        payload = BlockAndPersistPayload(outputArtifact = state.outputFor(phaseId)?.payload),
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
      policy = context.stepPolicy(phaseId),
    )
  }

  internal fun runPhase(
    context: FeatureTaskRuntimeRunLoopContext,
    args: RunPhaseArgs,
  ): PhaseOutcome {
    val phaseId = args.phaseId
    val declaration = phaseDeclarationForRun(context, args.request, phaseId)
    val run =
      buildPhaseRun(
        phaseId = phaseId,
        request = args.request,
        declaration = declaration,
        specSource = args.specSource,
        reentry = args.reentry,
        policy = context.stepPolicy(phaseId),
      )
    FeatureTaskRuntimeRunLoopPreLaunch.preLaunchBlock(
      context = context,
      run = run,
      state = args.state,
      observability = args.observability,
    )?.let { return it }
    return runPreparedPhase(context, run, args.state, args.observability)
  }

  internal fun phaseDeclarationForRun(
    context: FeatureTaskRuntimeRunLoopContext,
    request: FeatureTaskRuntimeRunRequest,
    phaseId: String,
  ): FeatureTaskRuntimePhaseDeclaration =
    phaseDeclaration(
      phaseId,
      request.runInvariants.featureSize,
      context.strategies.unselectedStepIds(strategySelectionFacts(request)),
    )

  internal fun buildPhaseRun(
    phaseId: String,
    request: FeatureTaskRuntimeRunRequest,
    declaration: FeatureTaskRuntimePhaseDeclaration,
    specSource: SpecSource,
    reentry: PendingReentry?,
    policy: PhaseStepPolicy,
  ): PhaseRun {
    val resolvedAgent =
      FeatureTaskRuntimeAgentResolver.resolve(
        phaseId = phaseId,
        assignment = request.agentAssignment,
        invokedAgentId = request.invokedAgentId,
      )
    return PhaseRun(
      phaseId = phaseId,
      declaration = declaration,
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
      policy = policy,
      reentry = reentry,
    )
  }

  internal fun runPreparedPhase(
    context: FeatureTaskRuntimeRunLoopContext,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val gateContext =
      context.copy(
        state = state,
        observability = observability,
      )
    return gateContext.strategyFor(run.phaseId)
      .runStep(run, gateContext, FeatureTaskRuntimeRunLoopSkeletonPhaseRunState(gateContext, run))
  }

  internal fun <T : Any> decideByStep(
    context: FeatureTaskRuntimeRunLoopContext,
    stepId: String,
    decide: (PhaseLoopRules, PhaseRunState) -> T?,
  ): T? = context.strategyFor(stepId).loopRules?.let { rules -> decide(rules, loopRuleState(context, stepId)) }

  internal fun <T : Any> decideByLoop(
    context: FeatureTaskRuntimeRunLoopContext,
    loopId: String,
    decide: (PhaseLoopRules, PhaseRunState) -> T?,
  ): T? =
    context.transitions.backwardEdges
      .firstOrNull { it.loopId == loopId }
      ?.let { edge -> decideByStep(context, edge.destinationPhaseId, decide) }

  internal fun forEachSlotRules(
    context: FeatureTaskRuntimeRunLoopContext,
    act: (PhaseLoopRules, PhaseRunState) -> Unit,
  ) {
    context.transitions.forwardPhaseIds.map(context::strategyFor).distinct().forEach { strategy ->
      strategy.loopRules?.let { rules -> act(rules, loopRuleState(context, strategy.entryStep)) }
    }
  }

  private fun loopRuleState(
    context: FeatureTaskRuntimeRunLoopContext,
    stepId: String,
  ): PhaseRunState =
    FeatureTaskRuntimeRunLoopSkeletonPhaseRunState(
      context,
      buildPhaseRun(
        phaseId = stepId,
        request = context.request,
        declaration = phaseDeclarationForRun(context, context.request, stepId),
        specSource = context.specSource,
        reentry = null,
        policy = context.stepPolicy(stepId),
      ),
    )

  fun effectiveEdgeIterationCount(
    state: FeatureTaskRuntimeRunState,
    edge: FeatureTaskRuntimeBackwardEdge,
  ): Int = state.edgeIterationCount(edge.loopId)

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
          .firstOrNull { it.value == loopId }?.key
      val latest =
        producer?.let { producing ->
          recorder.loadQuarantinedRecords(request.workflowId)
            .orEmpty()
            .lastOrNull { it.producingPhaseId == producing }
        }
      val recordId = latest?.recordIdentifier() ?: producer?.let { "$it#<unknown-iteration>" } ?: "<unknown>"
      return "Quarantine-and-regenerate loop '$loopId' exhausted its regeneration cap after $edgeIteration " +
        "attempt(s): the quarantined record '$recordId' produced by phase '${producer ?: "<unknown>"}' still " +
        "fails projection validation. The run blocks durably rather than regenerating past the cap; recover the " +
        "record out of band by deleting or migrating the offending row."
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
