package skillbill.engine.featuretask.slot.state

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.lifecycle.branch.FeatureTaskRuntimeBranchSetupOutcome
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptCollaborators
import skillbill.engine.featuretask.slot.attempt.PhaseStepAttempts
import skillbill.error.featuretask.PhaseRunFanOutUnsupportedError
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

/**
 * The state one run of the run loop reads and writes, built once per run by the entry that drives the loop. The loop,
 * its strategies, and the attempt loop reach the step records, the goal continuation, the settlements, and the
 * checkpoint writes only through the sub-ports it exposes, so an implementation decides where the run state lives.
 */
internal interface PhaseRunState :
  PhaseLaunchState,
  PhaseQualityGateReporting {
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

  /** The attempts the run's strategies launch their steps through. */
  val attemptLoop: PhaseStepAttempts

  /** The validator, clock, and diagnostics the run's attempts and loop read. */
  val collaborators: PhaseAttemptCollaborators

  /** The branch, git, and validation gates the run's steps consult. */
  val phaseGates: FeatureTaskRuntimePhaseGates

  /**
   * The units the fan-out step [stepId] runs. Only a state that keeps fan-out units supports it; the default fails
   * with a typed error.
   */
  fun fanOut(stepId: String): PhaseRunFanOut = throw PhaseRunFanOutUnsupportedError(stepId)

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

  /**
   * Records the review run [reviewRunId] and its [result] where the state keeps review history, emitting the pass's
   * stage telemetry only when [laneTelemetryRecorded] says the review strategy did not already record it. A durable
   * run records review history through its workflow records and keeps this a no-op.
   */
  fun recordReviewRun(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  ) = Unit

  /**
   * The target every review pass of the run reviews: [resolve] runs on the first pass and a state that pins the
   * target returns that result on later passes, so a fix that dirties the tree does not change what is re-reviewed.
   */
  fun pinnedReviewTarget(resolve: () -> ReviewTarget): ReviewTarget = resolve()
}

/** The quality-gate lifecycle a run reports while its quality gate step runs. */
internal interface PhaseQualityGateReporting {
  /**
   * Reports an optional gate absence. Required pack gates block before this hook.
   */
  fun qualityGateAbsent(stepName: String) = Unit

  /**
   * Reports that the quality-check gate of [stepName] started on the [detectedStack] pack, whose first gate run found
   * [initialFailureCount] failures. A durable run reports through its run telemetry.
   */
  fun qualityCheckStarted(
    stepName: String,
    detectedStack: String,
    initialFailureCount: Int,
  ) = Unit

  /**
   * Reports that the quality-check gate of [stepName] finished after [iterations] gate runs, whose last run found
   * [finalFailureCount] failures in [failingCheckNames]; the check passed exactly when that count is zero.
   */
  fun qualityCheckFinished(
    stepName: String,
    finalFailureCount: Int,
    failingCheckNames: List<String>,
    iterations: Int,
  ) = Unit
}
