package skillbill.engine.goalrunner.planning.state

import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunCheckpoints
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunGoal
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunSettlements
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepState
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptCollaborators
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptScope
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.engine.featuretask.slot.state.PhaseFanOutUnits
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.engine.goalrunner.planning.attempt.GoalPlanningStepAttempts
import skillbill.error.featuretask.GoalPlanningPhaseGatesUnsupportedError
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

internal class GoalPlanningPhaseRunState(
  private val facts: GoalPlanningRunFacts,
  override val progress: FeatureTaskRuntimeRunState,
  private val planning: GoalPlanningRunProgress,
  private val strategies: PhaseStrategyLookup,
  override val collaborators: PhaseAttemptCollaborators,
  override val specSource: SpecSource,
) : PhaseRunState {
  override val session: FeatureTaskRuntimeRunLoopSession =
    FeatureTaskRuntimeRunLoopSession(operatorBlockRetry = null, initialPendingReentry = null)
  override val records: PhaseRunRecords = InMemoryPhaseRunRecords(collaborators.clock, null)
  override val telemetry: FeatureTaskRuntimeRunObservability =
    FeatureTaskRuntimeRunObservability(records, facts, collaborators.diagnostics)
  override val goal: PhaseRunGoal = InMemoryPhaseRunGoal
  override val settlements: PhaseRunSettlements = InMemoryPhaseRunSettlements
  override val checkpoints: PhaseRunCheckpoints = InMemoryPhaseRunCheckpoints
  override val transitions: FeatureTaskRuntimeTransitionDeclaration = progress.transitions
  override val attemptLoop: PhaseStepAttempts = GoalPlanningStepAttempts(planning)
  override val phaseGates: FeatureTaskRuntimePhaseGates
    get() = throw GoalPlanningPhaseGatesUnsupportedError()

  private val planFanOut = GoalPlanningPlanFanOut(planning, this)

  override fun fanOut(stepId: String): PhaseRunFanOut = planFanOut

  override fun strategyFor(stepId: String): PhaseStrategy =
    strategies.strategyFor(stepId, strategySelectionFacts(facts))

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? =
    strategies.selectedOwnerOf(stepId, strategySelectionFacts(facts))

  override fun unselectedStepIds(): Set<String> = strategies.unselectedStepIds(strategySelectionFacts(facts))

  override fun step(run: PhaseRun): PhaseStepState =
    FeatureTaskRuntimeRunLoopStepState(
      PhaseAttemptScope(run.request, this),
      run,
    )

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
  parent: PhaseRunState,
  override val attemptLoop: PhaseStepAttempts,
) : PhaseRunState by parent

private class GoalPlanningPlanFanOut(
  private val planning: GoalPlanningRunProgress,
  private val runState: PhaseRunState,
) : PhaseRunFanOut {
  override val outputSink: AgentRunOutputSink = planning.outputSink

  override fun pendingUnits(): PhaseFanOutUnits = planning.pendingUnits()

  override fun pauseBefore(unitId: Int): PhaseOutcome? = planning.pauseBefore(unitId)

  override fun unitState(
    run: PhaseRun,
    unitId: Int,
    outputSink: AgentRunOutputSink,
  ): PhaseStepState {
    val unitRunState = GoalPlanningUnitRunState(runState, GoalPlanningStepAttempts(planning, unitId, outputSink))
    return FeatureTaskRuntimeRunLoopStepState(PhaseAttemptScope(run.request, unitRunState), run)
  }

  override fun settleUnit(
    unitId: Int,
    result: Result<PhaseOutcome>,
  ): PhaseOutcome? = planning.settleUnit(unitId, result)

  override fun completed(): PhaseOutcome = planning.completed()
}
