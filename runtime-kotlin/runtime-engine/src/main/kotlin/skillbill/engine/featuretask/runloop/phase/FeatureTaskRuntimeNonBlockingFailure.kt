package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.observability.completedEvent
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput

internal object FeatureTaskRuntimeNonBlockingFailure {
  fun settle(
    recorder: PhaseRunRecords,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
    observability: FeatureTaskRuntimeRunObservability,
    failure: PhaseFailureContinuationRequest,
  ): PhaseOutcome? {
    val run = failure.run
    val normalized = run.failureContinuationOutput ?: return null
    val iteration = failure.attempt.coerceAtLeast(1)
    val manifest = failure.manifest
    observability.diagnostics.error(
      "seam=phase_failure_continuation workflow=${run.request.workflowId} phase=${run.phaseId} " +
        "attempt=$iteration value_expected=verified_findings value_used=original_review_findings " +
        "reason=${boundedSchemaGateDetail(failure.reason)}",
    )
    val output = FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, normalized.canonicalJson, normalized)
    val phase =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        status = "completed",
        attemptCount = iteration,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = true,
        outputArtifact = normalized.canonicalJson,
        normalizedOutput = normalized,
        fileManifestBefore = manifest?.before.orEmpty(),
        fileManifestAfter = manifest?.after.orEmpty(),
        fileManifestIntroduced = manifest?.introduced.orEmpty(),
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
      )
    val start = phase.copy(status = "running", finished = false, normalizedOutput = null, outputArtifact = null)
    if (recorder.recordRequiredPhaseStart(start) is RequiredPhaseWrite.Rejected) {
      observability.diagnostics.error("seam=phase_failure_continuation phase=${run.phaseId} start_write=rejected")
    }
    val write = transitions.persistAuthoritativePhaseCompletion(recorder, phase, output)
    if (write is RequiredPhaseWrite.Rejected) {
      observability.diagnostics.error("seam=phase_failure_continuation phase=${run.phaseId} completion_write=rejected")
      transitions.recordSyntheticUpstreamCompletion(output)
    }
    observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(output)
  }
}

internal data class PhaseFailureContinuationRequest(
  val run: PhaseRun,
  val attempt: Int,
  val reason: String,
  val manifest: FeatureTaskRuntimePhaseFileManifest? = null,
)
