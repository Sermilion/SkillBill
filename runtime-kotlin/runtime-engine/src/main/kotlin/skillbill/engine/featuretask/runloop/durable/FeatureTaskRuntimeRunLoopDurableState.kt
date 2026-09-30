package skillbill.engine.featuretask.runloop.durable

import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindings
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunner
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptCollaborators
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptRunHost
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.engine.featuretask.slot.attempt.phaseAttemptLaunchCollaborationScope
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

internal class FeatureTaskRuntimeRunLoopDurableState(
  private val facts: FeatureTaskRuntimeRunFacts,
  override val progress: FeatureTaskRuntimeRunState,
  override val session: FeatureTaskRuntimeRunLoopSession,
  override val telemetry: FeatureTaskRuntimeRunObservability,
  override val specSource: SpecSource,
  private val executionPlan: ResolvedPhaseExecutionPlan,
  private val runner: FeatureTaskRuntimeRunner,
) : PhaseRunState {
  override val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    FeatureTaskRuntimeRunLoopStepBindingCoordinator()
  override val transitions: FeatureTaskRuntimeTransitionDeclaration = executionPlan.traversal
  private val workflowId = facts.workflowId
  private val repoRoot = facts.repoRoot

  override val records: PhaseRunRecords =
    DurablePhaseRunRecords(runner.recorder, runner.phaseGates.decomposeTerminalRecorder, facts.admittedExecution)
  override val goal: PhaseRunGoal = DurablePhaseRunGoal(runner.goalContinuationRecorder)
  override val settlements: PhaseRunSettlements = DurablePhaseRunSettlements(runner.phaseSettlementService)
  override val checkpoints: PhaseRunCheckpoints = DurablePhaseRunCheckpoints(runner.phaseGates.gitOperations)
  override val attemptLoop: PhaseStepAttempts = PhaseAttemptLoop
  override val collaborators: PhaseAttemptCollaborators =
    PhaseAttemptCollaborators(runner.clock, runner.diagnostics)
  override val phaseGates: FeatureTaskRuntimePhaseGates = runner.phaseGates

  override fun strategyFor(stepId: String): PhaseStrategy = runner.strategies.strategyFor(stepId, executionPlan)

  override fun runnerFor(stepId: String) = runner.strategies.runnerFor(stepId, executionPlan)

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? =
    runner.strategies.selectedOwnerOf(stepId, executionPlan)

  override fun unselectedStepIds(): Set<String> = executionPlan.unselectedStepIds

  override fun step(run: PhaseRun): PhaseAcceptedStepExecution {
    require(run.request === facts)
    require(run.phaseId in executionPlan.selectedStepIds)
    require(strategyFor(run.phaseId).policyFor(run.phaseId) == run.policy)
    stepBinding.beginStepBinding(run)
    return FeatureTaskRuntimeRunLoopStepBindings.create(
      phaseAttemptLaunchCollaborationScope(PhaseAttemptRunHost(run.request, this, run.phaseId, this)),
      run,
    )
  }

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
      settlements
        .findEnvelope(target.workflowId, stepName, target.attempt)
        ?.let { PhaseSettledEnvelopeRead.Found(it.envelope) }
        ?: PhaseSettledEnvelopeRead.None
    } catch (error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) {
      PhaseSettledEnvelopeRead.Failed(error)
    }
}
