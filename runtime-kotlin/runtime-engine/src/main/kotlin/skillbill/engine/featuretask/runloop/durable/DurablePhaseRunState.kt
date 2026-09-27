package skillbill.engine.featuretask.runloop.durable

import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.RunLoopPhaseStepState
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunner
import skillbill.engine.featuretask.slot.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.PhaseRunState
import skillbill.engine.featuretask.slot.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptScope
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

internal class DurablePhaseRunState(
  private val facts: FeatureTaskRuntimeRunFacts,
  override val progress: FeatureTaskRuntimeRunState,
  override val session: FeatureTaskRuntimeRunLoopSession,
  override val telemetry: FeatureTaskRuntimeRunObservability,
  override val specSource: SpecSource,
  override val transitions: FeatureTaskRuntimeTransitionDeclaration,
  private val runner: FeatureTaskRuntimeRunner,
) : PhaseRunState {
  private val workflowId = facts.workflowId
  private val repoRoot = facts.repoRoot

  override val records: PhaseRunRecords =
    DurablePhaseRunRecords(runner.recorder, runner.phaseGates.decomposeTerminalRecorder)
  override val goal: PhaseRunGoal = DurablePhaseRunGoal(runner.goalContinuationRecorder)
  override val settlements: PhaseRunSettlements = DurablePhaseRunSettlements(runner.phaseSettlementService)
  override val checkpoints: PhaseRunCheckpoints = DurablePhaseRunCheckpoints(runner.phaseGates.gitOperations)
  override val attemptLoop: PhaseAttemptLoop =
    PhaseAttemptLoop(runner.outputValidator, runner.phaseGates, runner.clock, runner.diagnostics)

  override fun strategyFor(stepId: String): PhaseStrategy =
    runner.strategies.strategyFor(stepId, strategySelectionFacts(facts))

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? =
    runner.strategies.selectedOwnerOf(stepId, strategySelectionFacts(facts))

  override fun unselectedStepIds(): Set<String> = runner.strategies.unselectedStepIds(strategySelectionFacts(facts))

  override fun step(run: PhaseRun): PhaseStepState = RunLoopPhaseStepState(PhaseAttemptScope(run.request, this), run)

  override fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    runner.phaseGates.branchSetupRunner.ensureFeatureBranch(facts, telemetry, guardPhase)

  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget =
    FeatureTaskRuntimePhaseSettlementTarget(workflowId, attempt)

  override fun launchObservation(stepName: String): PhaseLaunchObservation =
    PhaseLaunchObservation(
      activityStampSink =
        runner.activityStampWriter.sink(
          workflowId = workflowId,
          parentWorkflowId = facts.goalContinuation?.parentWorkflowId,
        ),
      worktreeEditObserver =
        runner.worktreeEditJournalWriter.observer(
          repoRoot = repoRoot,
          resolveWorkflowId = { workflowId },
          resolvePhaseId = { stepName },
        ),
    )

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
  ): PhaseSettledEnvelopeRead =
    try {
      settlements.findEnvelope(target.workflowId, stepName, target.attempt)
        ?.let { PhaseSettledEnvelopeRead.Found(it.envelope) }
        ?: PhaseSettledEnvelopeRead.None
    } catch (error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) {
      PhaseSettledEnvelopeRead.Failed(error)
    }
}
