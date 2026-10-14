package skillbill.engine.featuretask.runloop.durable

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.featuretask.phase.planning.PlanUnitSettlement
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunCheckpoints
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunGoal
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunSettlements
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.finalization.CommitPushManifestCompletion
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.planning.PlanDecompositionStop
import skillbill.engine.featuretask.runloop.planning.PlanUnitRecords
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
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
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskImplementationChecklistStore
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

internal class DurablePlanSubtasksFanOut(
  private val parent: PhaseRunState,
  private val facts: FeatureTaskRuntimeRunFacts,
  planner: FeatureTaskRuntimeDecompositionPlanner,
) : PhaseRunFanOut {
  private val snapshots = PlanBundleSnapshots(planner)
  private val unitStates = ConcurrentHashMap<Int, DurablePlanUnitRunState>()

  override val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE

  override fun pendingUnits(): PhaseFanOutUnits {
    val bundle =
      parent.records.loadPlanBundle(facts.workflowId)
        ?: return PhaseFanOutUnits.Stopped(PhaseOutcome.blocked(MISSING_BUNDLE_REASON))
    snapshots.drift(facts.repoRoot, facts.issueKey, bundle)?.let { reason ->
      return PhaseFanOutUnits.Stopped(PhaseOutcome.blocked(reason))
    }
    val unitRecords = parent.records.loadPhaseRecords(facts.workflowId).orEmpty()
    return PhaseFanOutUnits.Pending(
      bundle.subtasks
        .filterNot { snapshots.unitComplete(facts.repoRoot, it, unitRecords[unitRecordId(it.id)]) }
        .map { it.id },
    )
  }

  override fun pauseBefore(unitId: Int): PhaseOutcome? = null

  override fun unitState(
    unitId: Int,
    outputSink: AgentRunOutputSink,
  ): PhaseAcceptedStepExecution {
    val run = parent.stepBinding.requireAuthorizedFanOutWave()
    val unit = DurablePlanUnitRunState(parent, facts, unitId, outputSink)
    unitStates[unitId] = unit
    return unit.step(run)
  }

  override fun settleUnit(
    unitId: Int,
    result: Result<PhaseOutcome>,
  ): PhaseOutcome? {
    val outcome =
      result.getOrElse { error ->
        error.rethrowIfCooperativeCancellationOrInterruption()
        return PhaseOutcome.blocked("Planning subtask $unitId failed unexpectedly: ${error.message.orEmpty()}")
      }
    if (outcome !is PhaseOutcome.Completed) return outcome
    val bundle =
      parent.records.loadPlanBundle(facts.workflowId) ?: return PhaseOutcome.blocked(MISSING_BUNDLE_REASON)
    return when (val settled = snapshots.settleUnit(facts.repoRoot, facts.issueKey, bundle, unitId)) {
      is PlanUnitSettlement.Rejected -> PhaseOutcome.blocked(settled.reason)
      is PlanUnitSettlement.Planned ->
        if (recordUnit(unitId, outcome, settled.bundle)) {
          null
        } else {
          PhaseOutcome.blocked("Planning subtask $unitId could not be recorded.")
        }
    }
  }

  private fun recordUnit(
    unitId: Int,
    outcome: PhaseOutcome.Completed,
    bundle: FeatureTaskRuntimePlanBundle,
  ): Boolean {
    val settledStep =
      unitStates.remove(unitId)?.records?.loadPhaseRecords(facts.workflowId)?.get(STEP_ID)
    val unitRecord =
      PlanUnitRecords.settled(
        workflowId = facts.workflowId,
        subtaskId = unitId,
        attemptCount = settledStep?.attemptCount ?: outcome.output.iteration,
        resolvedAgentId =
          settledStep?.resolvedAgentId
            ?: FeatureTaskRuntimeAgentResolver
              .resolve(STEP_ID, facts.agentAssignment, facts.invokedAgentId)
              .resolvedAgentId,
        prose =
          outcome.output.output.value.ifBlank { bundle.subtask(unitId)?.planText.orEmpty() },
        now = parent.clock.instant(),
      )
    return parent.records.recordPlanBundleWithUnits(facts.workflowId, bundle, mapOf(unitId to unitRecord))
  }

  override fun completed(): PhaseOutcome {
    val bundle =
      parent.records.loadPlanBundle(facts.workflowId) ?: return PhaseOutcome.blocked(MISSING_BUNDLE_REASON)
    val ids = bundle.subtasks.map { it.id }
    val summary =
      "Planned subtasks ${ids.joinToString(", ")}; unit records " +
        ids.joinToString(", ") { unitRecordId(it) } + "."
    val output = PlanDecompositionStop.settledOutput(STEP_ID, summary, summary)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        STEP_ID,
        parent.progress.phase(STEP_ID).nextIteration,
        output.canonicalJson,
        output,
        null,
      ),
    )
  }

  private companion object {
    const val STEP_ID = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
    const val MISSING_BUNDLE_REASON = "plan_subtasks requires the plan bundle that the plan step records."

    fun unitRecordId(subtaskId: Int): String =
      FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(subtaskId)
  }
}

internal fun planSubtaskLaunchContext(
  bundle: FeatureTaskRuntimePlanBundle?,
  unitId: Int,
): String =
  checkNotNull(bundle?.subtask(unitId)) {
    "Plan subtask unit $unitId has no entry in the recorded plan bundle."
  }.let { subtask -> "\n\n## Planning session context\n\nCurrent governed sub-spec: ${subtask.specPath}\n" }

internal fun planSubtaskUpstreamRecords(
  records: Map<String, FeatureTaskRuntimePhaseRecord>,
): Map<String, FeatureTaskRuntimePhaseRecord> =
  records.filterKeys { phaseId ->
    phaseId in
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
        .getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS)
        .consumedUpstreamPhaseIds
  }

private class DurablePlanUnitRunState(
  private val parent: PhaseRunState,
  private val facts: FeatureTaskRuntimeRunFacts,
  private val unitId: Int,
  override val launchOutputSink: AgentRunOutputSink,
) : PhaseRunState {
  override var implementationChecklistStore: FeatureTaskImplementationChecklistStore?
    get() = parent.implementationChecklistStore
    set(value) {
      parent.implementationChecklistStore = value
    }
  override var commitPushManifestCompletion: CommitPushManifestCompletion?
    get() = parent.commitPushManifestCompletion
    set(value) {
      parent.commitPushManifestCompletion = value
    }
  override val launchPromptContext: String =
    planSubtaskLaunchContext(parent.records.loadPlanBundle(facts.workflowId), unitId)
  override val progress =
    FeatureTaskRuntimeRunState(
      initialRecords = planSubtaskUpstreamRecords(parent.records.loadPhaseRecords(facts.workflowId).orEmpty()),
      transitions = parent.transitions,
      resumeRulesFn = parent.progress.resumeRules,
    )
  override val session = FeatureTaskRuntimeRunLoopSession(operatorBlockRetry = null, initialPendingReentry = null)
  override val clock: Clock get() = parent.clock
  override val diagnostics: RuntimeDiagnostics get() = parent.diagnostics
  override val records: PhaseRunRecords = InMemoryPhaseRunRecords(parent.clock, null)
  override val specSource: SpecSource get() = parent.specSource
  override val transitions: FeatureTaskRuntimeTransitionDeclaration get() = parent.transitions
  override val telemetry: FeatureTaskRuntimeRunObservability =
    FeatureTaskRuntimeRunObservability(records, facts, parent.diagnostics)
  override val goal: PhaseRunGoal = InMemoryPhaseRunGoal
  override val settlements: PhaseRunSettlements = InMemoryPhaseRunSettlements
  override val checkpoints: PhaseRunCheckpoints = InMemoryPhaseRunCheckpoints
  override val attemptLoop: PhaseStepAttempts = PhaseAttemptLoop
  override val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    FeatureTaskRuntimeRunLoopStepBindingCoordinator()

  override fun strategyFor(stepId: String): PhaseStrategy = parent.strategyFor(stepId)

  override fun runnerFor(stepId: String) = parent.runnerFor(stepId)

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? = parent.selectedOwnerOf(stepId)

  override fun unselectedStepIds(): Set<String> = parent.unselectedStepIds()

  override fun stepLaunchAssignment(stepId: String) = parent.stepLaunchAssignment(stepId)

  override fun fanOut(stepId: String): PhaseRunFanOut = error("A plan subtask unit cannot start nested fan-out.")

  override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
    require(run.request === facts)
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
    parent.recordTokenUsage(stepName, inputTokens, outputTokens)
  }

  override fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead = PhaseSettledEnvelopeRead.None
}

