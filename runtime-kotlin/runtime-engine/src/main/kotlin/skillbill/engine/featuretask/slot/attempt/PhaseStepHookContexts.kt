package skillbill.engine.featuretask.slot.attempt

import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.slot.state.PhaseImplementationProgressRecords
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.nio.file.Path

internal interface PhaseAttemptLaunchHookContext : PhaseAttemptEnvironment {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess
  val session: FeatureTaskRuntimeRunSessionObservations
  val diagnostics: RuntimeDiagnostics

  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch?
}

internal interface PhasePullRequestLaunchHookContext : PhaseAttemptLaunchHookContext {
  fun pushResolvedBranchIfAhead(): String?
}

internal interface PhaseCommitLaunchHookContext : PhaseAttemptLaunchHookContext {
  fun recoverCommitUpstream(
    run: PhaseRun,
    upstreamReceipt: (String, Int) -> FeatureTaskRuntimePhaseOutput?,
  )
}

internal interface PhaseStepOutputContext : PhaseAttemptEnvironment {
  val progress: FeatureTaskRuntimeProgressSnapshotAccess
  val diagnostics: RuntimeDiagnostics
  val specSource: SpecSource

  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch?
}

internal const val NO_CHANGE_PAUSE_REASON =
  "Audit confirmed the no-change claim; awaiting an operator decision on the no-change pause."

internal interface PhaseAuditOutputContext : PhaseStepOutputContext {
  val auditsNoChangeClaim: Boolean

  val operatorReopened: Boolean

  /** Recorded audit rounds whose remaining list did not shrink after repair. */
  val nonShrinkingRounds: Int

  val missingBaselineRounds: Int

  fun recordMissingBaselineRound(capture: ValidatedOutputCapture)

  /** Records that the accepted audit round in [capture] did not shrink its remaining list. */
  fun recordNonShrinkingRound(capture: ValidatedOutputCapture)

  /** Settles the accepted audit round before normal forward routing. */
  fun settleAuditRound(
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    progressRejection: String?,
  ): AttemptResult?
}

/** The criterion progress records and selected spec text an implement step reads while it works one criterion. */
internal interface PhaseImplementationTurnAccess {
  val implementationRecords: PhaseImplementationProgressRecords

  /** The text of the spec file at [path]. */
  fun specText(path: Path): String
}

internal interface PhaseImplementationLaunchContext :
  PhaseAttemptLaunchHookContext,
  PhaseImplementationTurnAccess

internal interface PhaseImplementationOutputContext :
  PhaseStepOutputContext,
  PhaseImplementationTurnAccess {
  /** Blocks the implement step of [capture] with [reason] before its output is accepted. */
  fun blockImplementationTurn(
    capture: ValidatedOutputCapture,
    reason: String,
  ): AttemptResult
}

internal interface PhaseFindingEvidenceContext {
  val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory
  val specIntentProjectionResolver: SpecIntentProjectionResolver
}

internal interface PhasePlanningOutputContext : PhaseStepOutputContext {
  fun settleAuthoredBundle(capture: ValidatedOutputCapture): AttemptResult?

  fun withAuthoredParentSpecPath(
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput
}

internal interface PhaseAttemptTraversalHookContext : PhaseAttemptEnvironment

internal interface PhasePlanningTraversalContext : PhaseAttemptTraversalHookContext {
  fun settlePlanningStop(output: FeatureTaskRuntimePhaseOutput): String?
}
