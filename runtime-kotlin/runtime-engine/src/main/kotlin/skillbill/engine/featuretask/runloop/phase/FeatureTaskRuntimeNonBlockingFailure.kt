package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.observability.completedEvent
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseStepFailure
import skillbill.engine.featuretask.slot.PhaseStepFailureSource
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object FeatureTaskRuntimeNonBlockingFailure {
  fun settle(
    recorder: PhaseRunRecords,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
    observability: FeatureTaskRuntimeRunObservability,
    failure: PhaseFailureContinuationRequest,
  ): PhaseOutcome? {
    val run = failure.run
    val iteration = failure.attempt.coerceAtLeast(1)
    val continuation = continuationFor(failure) ?: return null
    observability.diagnostics.error(
      "seam=phase_failure_continuation workflow=${run.request.workflowId} phase=${run.phaseId} " +
        "attempt=$iteration" + continuation.diagnosticDetail?.let { " $it" }.orEmpty() +
        " reason=${boundedSchemaGateDetail(failure.reason)}",
    )
    val completion =
      RuntimeAuthoredCompletion(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        iteration = iteration,
        output = continuation.output,
        manifest = failure.manifest,
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
      )
    val write = persistRuntimeAuthoredCompletion(recorder, transitions, observability, completion)
    if (write is RequiredPhaseWrite.Rejected) {
      transitions.recordSyntheticUpstreamCompletion(completion.phaseOutput())
    }
    observability.completedEvent(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(completion.phaseOutput())
  }

  fun persistRuntimeAuthoredCompletion(
    recorder: PhaseRunRecords,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
    observability: FeatureTaskRuntimeRunObservability,
    completion: RuntimeAuthoredCompletion,
  ): RequiredPhaseWrite {
    val phaseId = completion.phaseId
    val manifest = completion.manifest
    val phase =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = completion.workflowId,
        phaseId = phaseId,
        status = "completed",
        attemptCount = completion.iteration,
        resolvedAgentId = completion.resolvedAgentId,
        finished = true,
        outputArtifact = completion.output.canonicalJson,
        normalizedOutput = completion.output,
        fileManifestBefore = manifest?.before.orEmpty(),
        fileManifestAfter = manifest?.after.orEmpty(),
        fileManifestIntroduced = manifest?.introduced.orEmpty(),
        loopId = completion.loopId,
        edgeIteration = completion.edgeIteration,
      )
    val start = phase.copy(status = "running", finished = false, normalizedOutput = null, outputArtifact = null)
    if (recorder.recordRequiredPhaseStart(start) is RequiredPhaseWrite.Rejected) {
      observability.diagnostics.error("seam=phase_failure_continuation phase=$phaseId start_write=rejected")
    }
    val write = transitions.persistAuthoritativePhaseCompletion(recorder, phase, completion.phaseOutput())
    if (write is RequiredPhaseWrite.Rejected) {
      observability.diagnostics.error("seam=phase_failure_continuation phase=$phaseId completion_write=rejected")
    }
    return write
  }

  private fun continuationFor(failure: PhaseFailureContinuationRequest): PhaseFailureContinuationOutput? {
    val run = failure.run
    val stop =
      PhaseStepFailure(
        reason = failure.reason,
        source = failure.source,
        failureCode = failure.failureCode,
        owedFindingRefs = failure.owedFindingRefs,
      )
    return run.failureContinuation?.outputFor(run, stop) ?: run.failureContinuationOutput
  }
}

internal data class PhaseFailureContinuationRequest(
  val run: PhaseRun,
  val attempt: Int,
  val reason: String,
  val manifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val source: PhaseStepFailureSource = PhaseStepFailureSource.BLOCK,
  val failureCode: String? = null,
  val owedFindingRefs: Set<String> = emptySet(),
)

internal data class RuntimeAuthoredCompletion(
  val workflowId: String,
  val phaseId: String,
  val resolvedAgentId: String,
  val iteration: Int,
  val output: NormalizedFeatureTaskRuntimePhaseOutput,
  val manifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val loopId: String? = null,
  val edgeIteration: Int? = null,
) {
  fun phaseOutput(): FeatureTaskRuntimePhaseOutput =
    FeatureTaskRuntimePhaseOutput(phaseId, iteration, output.canonicalJson, output)
}
