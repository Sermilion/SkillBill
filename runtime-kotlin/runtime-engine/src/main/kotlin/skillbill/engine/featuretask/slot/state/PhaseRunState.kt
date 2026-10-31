package skillbill.engine.featuretask.slot.state

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.finalization.CommitPushManifestCompletion
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopStepBindingCoordinator
import skillbill.engine.featuretask.slot.ModelDirectiveSupplements
import skillbill.engine.featuretask.slot.PhaseBindingFactory
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.PhaseInstructionFileSource
import skillbill.ports.taskruntime.FeatureTaskImplementationChecklistStore
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import java.time.Clock

/**
 * The state one run of the run loop reads and writes, built once per run by the entry that drives the loop. The loop,
 * its strategies, and the attempt loop reach the step records, the goal continuation, the settlements, and the
 * checkpoint writes only through the sub-ports it exposes, so an implementation decides where the run state lives.
 */
public interface PhaseRunState :
  PhaseLaunchState,
  PhaseQualityGateReporting {
  /** Coordinates coordinator dispatch with [step] binding lifetime for this run. */
  public val stepBinding: FeatureTaskRuntimeRunLoopStepBindingCoordinator

  /** The in-memory progress of the run: step records, iterations, completions, and the review generation. */
  public val progress: FeatureTaskRuntimeProgressSnapshotAccess

  /** The run's session: the resolved branch, the pending re-entry, and the terminal report. */
  public val session: FeatureTaskRuntimeRunSessionObservations

  /** The step lifecycle events and telemetry the run emits. */
  public val telemetry: FeatureTaskRuntimeRunObservability

  /** The step records, ledger, evidence, review checkpoint, and gate progress of the run. */
  public val records: PhaseRunRecords

  /** The goal-continuation state of a goal child run. */
  public val goal: PhaseRunGoal

  /** The step settlements launched agents recorded. */
  public val settlements: PhaseRunSettlements

  /** The subtask commit and checkpoint ref writes. */
  public val checkpoints: PhaseRunCheckpoints

  /** The spec the run implements. */
  public val specSource: SpecSource

  /** The transitions the run traverses. */
  public val transitions: FeatureTaskRuntimeTransitionDeclaration

  /** The attempts the run's strategies launch their steps through. */
  public val attemptLoop: PhaseStepAttempts

  public val clock: Clock

  public val diagnostics: RuntimeDiagnostics

  /** The private implementation checklist for this run, when the run entry supplied one. */
  public var implementationChecklistStore: FeatureTaskImplementationChecklistStore?

  /** Writes the completed decomposition manifest before a goal child's final commit. */
  public var commitPushManifestCompletion: CommitPushManifestCompletion?

  /**
   * The units the fan-out step [stepId] runs. Only a state that keeps fan-out units supports it; the others fail with
   * a typed error.
   */
  public fun fanOut(stepId: String): PhaseRunFanOut

  /** The strategy selected for [stepId] in this run. */
  public fun strategyFor(stepId: String): PhaseStrategy

  public fun runnerFor(stepId: String): PhaseRunner

  /** The selected strategy that declares [stepId] among its steps, or null when no selected strategy does. */
  public fun selectedOwnerOf(stepId: String): PhaseStrategy?

  public val modelDirectiveSupplements: ModelDirectiveSupplements

  /** Reads the repository's committed `.skill-bill/phases` instruction files at launch. */
  public val phaseInstructionSource: PhaseInstructionFileSource

  /** The steps of the definition no strategy selected for this run owns. */
  public fun unselectedStepIds(): Set<String>

  public fun stepLaunchAssignment(stepId: String): StepLaunchAssignment?

  /** The factory that builds the state [run] reads and writes for one call of its step. */
  public fun bindings(run: PhaseRun): PhaseBindingFactory

  /** Resolves, checks out, and records the feature branch file-mutating steps run on, reported under [guardPhase]. */
  public fun ensureFeatureBranch(guardPhase: String): FeatureTaskRuntimeBranchSetupOutcome

  /**
   * Records the review run [reviewRunId] and its [result] where the state keeps review history, emitting the pass's
   * stage telemetry only when [laneTelemetryRecorded] says the review strategy did not already record it. A state whose
   * review history is recorded by another owner records nothing.
   */
  public fun recordReviewRun(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  )

  /**
   * The target every review pass of the run reviews: [resolve] runs on the first pass and a state that pins the
   * target returns that result on later passes, so a fix that dirties the tree does not change what is re-reviewed.
   */
  public fun pinnedReviewTarget(resolve: () -> ReviewTarget): ReviewTarget = resolve()
}

/** The quality-gate lifecycle a run reports while its quality gate step runs. */
public interface PhaseQualityGateReporting {
  /**
   * Reports an optional gate absence. Required pack gates block before this hook.
   */
  public fun qualityGateAbsent(stepName: String)

  /**
   * Reports that the quality-check gate of [stepName] started on the [detectedStack] pack, whose first gate run found
   * [initialFailureCount] failures. A durable run reports through its run telemetry.
   */
  public fun qualityCheckStarted(
    stepName: String,
    detectedStack: String,
    initialFailureCount: Int,
  )

  /**
   * Reports that the quality-check gate of [stepName] finished after [iterations] gate runs, whose last run found
   * [finalFailureCount] failures in [failingCheckNames]; the check passed exactly when that count is zero.
   */
  public fun qualityCheckFinished(
    stepName: String,
    finalFailureCount: Int,
    failingCheckNames: List<String>,
    iterations: Int,
  )
}
