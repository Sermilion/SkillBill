package skillbill.engine.goalrunner.planning.state

import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunCheckpoints
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunGoal
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunSettlements
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.engine.featuretask.slot.attempt.phaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseFanOutUnits
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningStepAttempts
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import java.time.Clock

internal class GoalPlanningPhaseRunState(
  internal val facts: GoalPlanningRunFacts,
  override val progress: FeatureTaskRuntimeRunState,
  private val planning: GoalPlanningRunProgress,
  private val strategies: PhaseStrategyLookup,
  private val executionPlan: ResolvedPhaseExecutionPlan,
  override val clock: Clock,
  override val diagnostics: RuntimeDiagnostics,
  override val specSource: SpecSource,
) : PhaseRunState {
  override val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    FeatureTaskRuntimeRunLoopStepBindingCoordinator()
  override val session: FeatureTaskRuntimeRunLoopSession =
    FeatureTaskRuntimeRunLoopSession(operatorBlockRetry = null, initialPendingReentry = null)
  override val records: PhaseRunRecords = InMemoryPhaseRunRecords(clock, null)
  override val telemetry: FeatureTaskRuntimeRunObservability =
    FeatureTaskRuntimeRunObservability(records, facts, diagnostics)
  override val goal: PhaseRunGoal = InMemoryPhaseRunGoal
  override val settlements: PhaseRunSettlements = InMemoryPhaseRunSettlements
  override val checkpoints: PhaseRunCheckpoints = InMemoryPhaseRunCheckpoints
  override val transitions: FeatureTaskRuntimeTransitionDeclaration = progress.transitions
  override val attemptLoop: PhaseStepAttempts = GoalPlanningStepAttempts(planning)

  private val planFanOut = GoalPlanningPlanFanOut(planning, this)

  override fun fanOut(stepId: String): PhaseRunFanOut = planFanOut

  internal fun planningUnitState(
    unitId: Int,
    outputSink: AgentRunOutputSink,
  ): PhaseRunState = GoalPlanningUnitRunState(this, unitId, GoalPlanningStepAttempts(planning, unitId, outputSink))

  override fun strategyFor(stepId: String): PhaseStrategy = strategies.strategyFor(stepId, executionPlan)

  override fun runnerFor(stepId: String) = strategies.runnerFor(stepId, executionPlan)

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? = strategies.selectedOwnerOf(stepId, executionPlan)

  override fun unselectedStepIds(): Set<String> = executionPlan.unselectedStepIds

  internal fun authorizeSelectedStepRun(run: PhaseRun) {
    require(run.request === facts)
    require(run.phaseId in executionPlan.selectedStepIds)
    require(strategyFor(run.phaseId).policyFor(run.phaseId) == run.policy)
  }

  override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
    authorizeSelectedStepRun(run)
    stepBinding.beginStepBinding(run)
    return FeatureTaskRuntimeRunLoopStepBindings.create(
      phaseAttemptLaunchCollaborationScope(PhaseAttemptRunHost(run, this)),
      run,
    )
  }

  override fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    FeatureTaskRuntimeBranchSetupOutcome.unchanged()

  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget? = null

  override fun launchObservation(stepName: String): PhaseLaunchObservation =
    PhaseLaunchObservation(AgentRunActivityStampSink.NONE, AgentRunWorktreeEditObserver.NONE)

  override fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  ) {
    progress.recordPhaseTokenUsage(stepName, inputTokens, outputTokens)
  }

  override fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead = PhaseSettledEnvelopeRead.None
}

private class GoalPlanningUnitRunState(
  private val parent: GoalPlanningPhaseRunState,
  private val unitId: Int,
  override val attemptLoop: PhaseStepAttempts,
) : PhaseRunState {
  override val progress =
    FeatureTaskRuntimeRunState(
      initialRecords = emptyMap(),
      transitions = parent.transitions,
      resumeRulesFn = parent.progress.resumeRules,
    )
  override val session = FeatureTaskRuntimeRunLoopSession(operatorBlockRetry = null, initialPendingReentry = null)
  override val clock get() = parent.clock
  override val diagnostics get() = parent.diagnostics
  override val records: PhaseRunRecords = InMemoryPhaseRunRecords(parent.clock, null)
  override val specSource get() = parent.specSource
  override val transitions get() = parent.transitions
  override val telemetry: FeatureTaskRuntimeRunObservability =
    FeatureTaskRuntimeRunObservability(records, parent.facts, parent.diagnostics)
  override val goal: PhaseRunGoal = InMemoryPhaseRunGoal
  override val settlements: PhaseRunSettlements = InMemoryPhaseRunSettlements
  override val checkpoints: PhaseRunCheckpoints = InMemoryPhaseRunCheckpoints
  override val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    FeatureTaskRuntimeRunLoopStepBindingCoordinator()

  override fun strategyFor(stepId: String): PhaseStrategy = parent.strategyFor(stepId)

  override fun runnerFor(stepId: String) = parent.runnerFor(stepId)

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? = parent.selectedOwnerOf(stepId)

  override fun unselectedStepIds(): Set<String> = parent.unselectedStepIds()

  override fun fanOut(stepId: String): PhaseRunFanOut = error("Goal planning unit state cannot start nested fan-out.")

  override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
    parent.authorizeSelectedStepRun(run)
    parent.stepBinding.beginStepBinding(run, unitId)
    return FeatureTaskRuntimeRunLoopStepBindings.create(
      phaseAttemptLaunchCollaborationScope(PhaseAttemptRunHost(run, this)),
      run,
      unitId,
      bindingCoordinator = parent.stepBinding,
    )
  }

  override fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    FeatureTaskRuntimeBranchSetupOutcome.unchanged()

  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget? = null

  override fun launchObservation(stepName: String): PhaseLaunchObservation =
    PhaseLaunchObservation(AgentRunActivityStampSink.NONE, AgentRunWorktreeEditObserver.NONE)

  override fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  ) {
    progress.recordPhaseTokenUsage(stepName, inputTokens, outputTokens)
  }

  override fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead = PhaseSettledEnvelopeRead.None
}

private class GoalPlanningPlanFanOut(
  private val planning: GoalPlanningRunProgress,
  private val runState: GoalPlanningPhaseRunState,
) : PhaseRunFanOut {
  override val outputSink: AgentRunOutputSink = planning.outputSink

  override fun pendingUnits(): PhaseFanOutUnits = planning.pendingUnits()

  override fun pauseBefore(unitId: Int): PhaseOutcome? = planning.pauseBefore(unitId)

  override fun unitState(
    unitId: Int,
    outputSink: AgentRunOutputSink,
  ): PhaseAcceptedStepExecution {
    val run = runState.stepBinding.requireAuthorizedFanOutWave()
    val unitRunState = runState.planningUnitState(unitId, outputSink)
    return unitRunState.step(run)
  }

  override fun settleUnit(
    unitId: Int,
    result: Result<PhaseOutcome>,
  ): PhaseOutcome? = planning.settleUnit(unitId, result)

  override fun completed(): PhaseOutcome = planning.completed()
}
