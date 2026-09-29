package skillbill.engine.featuretask.slot.attempt

import skillbill.application.review.spec.SpecIntentProjectionResolver
import skillbill.engine.featuretask.review.finding.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSessionObservations
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunProgressObservations
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal interface PhaseAttemptLaunchHookContext : PhaseAttemptEnvironment {
  val progress: FeatureTaskRuntimeRunProgressObservations
  val session: FeatureTaskRuntimeRunLoopSessionObservations
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator
  val diagnostics: RuntimeDiagnostics

  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch?
}

internal interface PhasePullRequestLaunchHookContext : PhaseAttemptLaunchHookContext {
  fun pushResolvedBranchIfAhead(): String?
}

internal interface PhaseCommitLaunchHookContext : PhaseAttemptLaunchHookContext {
  fun recoverCommitUpstream(run: PhaseRun)
}

internal interface PhaseStepOutputContext : PhaseAttemptEnvironment {
  val progress: FeatureTaskRuntimeRunProgressObservations
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator
  val diagnostics: RuntimeDiagnostics
  val specSource: SpecSource

  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch?
}

internal interface PhaseAuditOutputContext : PhaseStepOutputContext {
  fun settleAuditRound(
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult?
}

internal interface PhaseFindingEvidenceContext {
  val findingVerificationBoundaryMemory: FeatureTaskRuntimeFindingVerificationBoundaryMemory
  val specIntentProjectionResolver: SpecIntentProjectionResolver
}

internal interface PhaseAttemptTraversalHookContext : PhaseAttemptEnvironment

internal interface PhasePlanningTraversalContext : PhaseAttemptTraversalHookContext {
  fun settlePlanningStop(output: FeatureTaskRuntimePhaseOutput): String?
}
