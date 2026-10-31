package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ValidationTestProbePayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.qualitygate.ValidateProbeActivation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asValidationTestProbesWireMap
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbe
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeParse
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeParser
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeResult

internal object ValidateProbeSettlement {
  data class Site(
    val diagnostics: RuntimeDiagnostics,
    val workflowId: String,
    val phaseId: String,
  )

  fun settle(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = output.envelopeWireMap()
    if (!ownsProbes(run, envelope)) return output
    return storing(
      output,
      auditProseValue(envelope).orEmpty(),
      Site(context.diagnostics, context.request.workflowId, run.phaseId),
    )
  }

  fun storing(
    output: NormalizedFeatureTaskRuntimePhaseOutput,
    prose: String,
    site: Site,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val probes: List<ValidationTestProbe> =
      when (val parsed = ValidationTestProbeParser.parse(prose)) {
        is ValidationTestProbeParse.Parsed -> parsed.probes.also { warnUnavailable(site, it) }
        is ValidationTestProbeParse.Malformed -> {
          warnEmpty(site, parsed)
          emptyList()
        }
      }
    return output.withTestProbes(probes)
  }

  private fun ownsProbes(
    run: PhaseRun,
    envelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ): Boolean =
    ValidateProbeActivation.applies(run.request, run.validationGateTriage) &&
      (envelope[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() == WorkflowStepStatus.COMPLETED

  private fun warnUnavailable(
    site: Site,
    probes: List<ValidationTestProbe>,
  ) {
    val unavailable = probes.filter { it.result == ValidationTestProbeResult.NOT_RUN }
    if (unavailable.isEmpty()) return
    RuntimeDiagnosticsBestEffortWarning.record(
      site.diagnostics,
      "Validation test probes were unavailable for workflow ${site.workflowId}, phase '${site.phaseId}': " +
        "${unavailable.joinToString("; ") { "${it.testRef} (${it.reason})" }}. " +
        "The probes are stored as not_run and validation settles on its normal checks.",
    )
  }

  private fun warnEmpty(
    site: Site,
    malformed: ValidationTestProbeParse.Malformed,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      site.diagnostics,
      "Validation test probe settlement for workflow ${site.workflowId}, phase '${site.phaseId}' " +
        "could not extract test probes from the prose report. " +
        "An empty test_probes list is stored and validation settles on its normal checks. " +
        "Cause: ${boundedSchemaGateDetail(malformed.reason)}",
    )
  }

  private fun NormalizedFeatureTaskRuntimePhaseOutput.withTestProbes(
    probes: List<ValidationTestProbe>,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val envelope = envelopeWireMap().toMutableMap()
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty().toMutableMap()
    produced[ValidationTestProbePayloadKeys.TEST_PROBES] =
      probes.asValidationTestProbesWireMap()[ValidationTestProbePayloadKeys.TEST_PROBES]
    envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
  }
}
