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
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.finalization.CommitPushManifestCompletion
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.planning.PlanDecompositionStop
import skillbill.engine.featuretask.runloop.planning.PlanUnit
import skillbill.engine.featuretask.runloop.planning.PlanUnitAttempt
import skillbill.engine.featuretask.runloop.planning.PlanUnitRecords
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.ModelDirectiveSupplements
import skillbill.engine.featuretask.slot.PhaseBindingFactory
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
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

internal class FeatureTaskRuntimeRunLoopPlanSubtasksFanOut(
  private val parent: PhaseRunState,
  private val facts: FeatureTaskRuntimeRunFacts,
  private val stepId: String,
  planner: FeatureTaskRuntimeDecompositionPlanner,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
) : PhaseRunFanOut {
  private val snapshots = PlanBundleSnapshots(planner)
  private val unitStates = ConcurrentHashMap<Int, FeatureTaskRuntimeRunLoopPlanUnitState>()

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

  override fun <B : PhaseAcceptedStepExecution> unitState(
    unitId: Int,
    outputSink: AgentRunOutputSink,
    descriptor: PhaseStepDescriptor<B>,
  ): B {
    val run = parent.stepBinding.requireAuthorizedFanOutWave()
    val unit =
      FeatureTaskRuntimeRunLoopPlanUnitState(
        parent,
        facts,
        PlanUnit(facts.workflowId, stepId, unitId),
        outputSink,
        runLoopEntry,
      )
    unitStates[unitId] = unit
    return descriptor.bind(unit.bindings(run))
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
      unitStates.remove(unitId)?.records?.loadPhaseRecords(facts.workflowId)?.get(stepId)
    val unitRecord =
      PlanUnitRecords.settled(
        unit = PlanUnit(facts.workflowId, stepId, unitId),
        attempt =
          PlanUnitAttempt(
            attemptCount = settledStep?.attemptCount ?: outcome.output.iteration,
            resolvedAgentId =
              settledStep?.resolvedAgentId
                ?: FeatureTaskRuntimeAgentResolver
                  .resolve(stepId, facts.agentAssignment, facts.invokedAgentId)
                  .resolvedAgentId,
          ),
        prose = outcome.output.output.value.ifBlank { bundle.subtask(unitId)?.planText.orEmpty() },
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
    val output = PlanDecompositionStop.settledOutput(stepId, summary, summary)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        stepId,
        parent.progress.phase(stepId).nextIteration,
        output.canonicalJson,
        output,
        null,
      ),
    )
  }

  private companion object {
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
  stepId: String,
): Map<String, FeatureTaskRuntimePhaseRecord> {
  val consumed = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.getValue(stepId).consumedUpstreamPhaseIds
  return records.filterKeys { phaseId -> phaseId in consumed }
}

private class FeatureTaskRuntimeRunLoopPlanUnitState(
  private val parent: PhaseRunState,
  private val facts: FeatureTaskRuntimeRunFacts,
  private val unit: PlanUnit,
  override val launchOutputSink: AgentRunOutputSink,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
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
    planSubtaskLaunchContext(parent.records.loadPlanBundle(facts.workflowId), unit.subtaskId)
  override val progress =
    FeatureTaskRuntimeRunState(
      initialRecords =
        planSubtaskUpstreamRecords(parent.records.loadPhaseRecords(facts.workflowId).orEmpty(), unit.stepId),
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

  override val modelDirectiveSupplements: ModelDirectiveSupplements get() = parent.modelDirectiveSupplements

  override fun unselectedStepIds(): Set<String> = parent.unselectedStepIds()

  override fun stepLaunchAssignment(stepId: String) = parent.stepLaunchAssignment(stepId)

  override fun fanOut(stepId: String): PhaseRunFanOut = error("A plan subtask unit cannot start nested fan-out.")

  override fun bindings(run: PhaseRun): PhaseBindingFactory =
    runLoopEntry.context(facts, this).fanOutUnitBindings(run, unit.subtaskId, parent.stepBinding)

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
