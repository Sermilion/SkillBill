package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
import skillbill.engine.featuretask.slot.state.PhaseRunCheckpoints
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

/**
 * The state one run of the run loop reads and writes, built once per run by the entry that drives the loop. The loop,
 * its strategies, and the attempt loop reach the step records, the goal continuation, the settlements, and the
 * checkpoint writes only through the sub-ports it exposes, so an implementation decides where the run state lives.
 */
internal interface PhaseRunState : PhaseLaunchState {
  /** The in-memory progress of the run: step records, iterations, completions, and the review generation. */
  val progress: FeatureTaskRuntimeRunState

  /** The run's session: the resolved branch, the pending re-entry, and the terminal report. */
  val session: FeatureTaskRuntimeRunLoopSession

  /** The step lifecycle events and telemetry the run emits. */
  val telemetry: FeatureTaskRuntimeRunObservability

  /** The step records, ledger, evidence, review checkpoint, and gate progress of the run. */
  val records: PhaseRunRecords

  /** The goal-continuation state of a goal child run. */
  val goal: PhaseRunGoal

  /** The step settlements launched agents recorded. */
  val settlements: PhaseRunSettlements

  /** The subtask commit and checkpoint ref writes. */
  val checkpoints: PhaseRunCheckpoints

  /** The spec the run implements. */
  val specSource: SpecSource

  /** The transitions the run traverses. */
  val transitions: FeatureTaskRuntimeTransitionDeclaration

  /** The attempt loop the run's strategies launch their steps through. */
  val attemptLoop: PhaseAttemptLoop

  /** The strategy selected for [stepId] in this run. */
  fun strategyFor(stepId: String): PhaseStrategy

  /** The selected strategy that declares [stepId] among its steps, or null when no selected strategy does. */
  fun selectedOwnerOf(stepId: String): PhaseStrategy?

  /** The steps of the definition no strategy selected for this run owns. */
  fun unselectedStepIds(): Set<String>

  /** The state [run] reads and writes for one call of its step. */
  fun step(run: PhaseRun): PhaseStepState

  /** Resolves, checks out, and records the feature branch file-mutating steps run on, reported under [guardPhase]. */
  fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome
}
