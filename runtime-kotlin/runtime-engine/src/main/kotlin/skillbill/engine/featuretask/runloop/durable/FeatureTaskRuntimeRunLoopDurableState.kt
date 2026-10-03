package skillbill.engine.featuretask.runloop.durable

import me.tatarka.inject.annotations.Inject
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupRunner
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.engine.worktreeedit.WorktreeEditJournalWriter
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import java.time.Clock

internal class FeatureTaskRuntimeRunLoopDurableState(
  override val progress: FeatureTaskRuntimeRunState,
  override val session: FeatureTaskRuntimeRunLoopSession,
  override val telemetry: FeatureTaskRuntimeRunObservability,
  override val specSource: SpecSource,
  private val executionPlan: ResolvedPhaseExecutionPlan,
  private val strategies: PhaseStrategyLookup,
  override val goal: PhaseRunGoal,
  override val settlements: PhaseRunSettlements,
  override val checkpoints: PhaseRunCheckpoints,
  private val launch: FeatureTaskRuntimeRunLoopDurableLaunch,
  override val clock: Clock,
) : PhaseRunState {
  private val facts get() = telemetry.request
  override val records get() = telemetry.recorder
  override val diagnostics get() = telemetry.diagnostics

  override val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator =
    FeatureTaskRuntimeRunLoopStepBindingCoordinator()
  override val transitions: FeatureTaskRuntimeTransitionDeclaration = executionPlan.traversal
  override val attemptLoop: PhaseStepAttempts = PhaseAttemptLoop

  override fun strategyFor(stepId: String): PhaseStrategy = strategies.strategyFor(stepId, executionPlan)

  override fun runnerFor(stepId: String) = strategies.runnerFor(stepId, executionPlan)

  override fun selectedOwnerOf(stepId: String): PhaseStrategy? = strategies.selectedOwnerOf(stepId, executionPlan)

  override fun unselectedStepIds(): Set<String> = executionPlan.unselectedStepIds

  override fun step(run: PhaseRun): PhaseAcceptedStepExecution = launch.step(facts, this, run)

  override fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome =
    launch.ensureFeatureBranch(facts, telemetry, guardPhase)

  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget =
    FeatureTaskRuntimePhaseSettlementTarget(facts.workflowId, attempt)

  override fun launchObservation(stepName: String): PhaseLaunchObservation = launch.observation(facts, stepName)

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

@Inject
class FeatureTaskRuntimeRunLoopDurableLaunch(
  private val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  private val activityStampWriter: AgentActivityStampWriter,
  private val worktreeEditJournalWriter: WorktreeEditJournalWriter,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
) {
  internal fun step(
    facts: FeatureTaskRuntimeRunFacts,
    state: PhaseRunState,
    run: PhaseRun,
  ): PhaseAcceptedStepExecution = runLoopEntry.context(facts, state).acceptedStep(run)

  internal fun ensureFeatureBranch(
    facts: FeatureTaskRuntimeRunFacts,
    telemetry: FeatureTaskRuntimeRunObservability,
    guardPhase: String,
  ): FeatureTaskRuntimeBranchSetupOutcome = branchSetupRunner.ensureFeatureBranch(facts, telemetry, guardPhase)

  internal fun observation(
    facts: FeatureTaskRuntimeRunFacts,
    stepName: String,
  ): PhaseLaunchObservation =
    PhaseLaunchObservation(
      activityStampSink =
        activityStampWriter.sink(
          workflowId = facts.workflowId,
          parentWorkflowId = facts.goalContinuation?.parentWorkflowId,
        ),
      worktreeEditObserver =
        worktreeEditJournalWriter.observer(
          repoRoot = facts.repoRoot,
          resolveWorkflowId = { facts.workflowId },
          resolvePhaseId = { stepName },
        ),
    )
}
