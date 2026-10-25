package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ValidationTestProbePayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.output.rejectionRuleMarker
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.qualitygate.ValidateProbeActivation
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
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
  private val CORRECTION_RULE = FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_TEST_PROBES.name

  data class Site(
    val diagnostics: RuntimeDiagnostics,
    val workflowId: String,
    val phaseId: String,
  )

  fun rejection(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? =
    if (ownsProbes(run, outputMap)) {
      correction(auditProseValue(outputMap).orEmpty(), correctionRequested(context, run))
    } else {
      null
    }

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

  fun correction(
    prose: String,
    correctionRequested: Boolean,
  ): String? {
    val malformed = ValidationTestProbeParser.parse(prose) as? ValidationTestProbeParse.Malformed ?: return null
    if (correctionRequested) return null
    return "${malformed.code.name}: ${malformed.reason} ${ValidationTestProbeParser.LINE_GRAMMAR}"
  }

  private fun correctionRequested(
    context: PhaseStepOutputContext,
    run: PhaseRun,
  ): Boolean =
    context.progress
      .blockedReasons(run.phaseId, run.reentry?.loopId, run.reentry?.edgeIteration)
      .any(::spendsCorrection)

  fun spendsCorrection(reason: String): Boolean = reason.contains(rejectionRuleMarker(CORRECTION_RULE))

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
        "expected the test_probes artifact after one correction and got a malformed report. " +
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
