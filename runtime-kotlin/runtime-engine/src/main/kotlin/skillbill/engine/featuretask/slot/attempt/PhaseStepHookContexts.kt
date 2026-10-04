package skillbill.engine.featuretask.slot.attempt

import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

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

internal interface PhaseAuditOutputContext : PhaseStepOutputContext {
  val operatorReopened: Boolean

  /** Recorded audit rounds whose remaining list did not shrink after repair. */
  val nonShrinkingRounds: Int

  val missingBaselineRounds: Int

  fun recordMissingBaselineRound(capture: ValidatedOutputCapture)

  /** Records that the accepted audit round in [capture] did not shrink its remaining list. */
  fun recordNonShrinkingRound(capture: ValidatedOutputCapture)

  fun settleAuditRound(
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    progressRejection: String?,
  ): AttemptResult?
}

internal interface PhaseFindingEvidenceContext {
  val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory
  val specIntentProjectionResolver: SpecIntentProjectionResolver
}

internal interface PhasePlanningLaunchContext : PhaseAttemptLaunchHookContext {
  fun existingBundleReason(): String?
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
