package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_TEST_PROBES_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidValidationTestProbes
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbe
import skillbill.workflow.taskruntime.model.validation.ValidationTestProbeShape
import skillbill.contracts.workflow.featuretask.ValidationTestProbePayloadKeys as Keys

fun List<ValidationTestProbe>.asValidationTestProbesWireMap(): FeatureTaskRuntimeWorkflowArtifactMap {
  ValidationTestProbeShape.issue(this)?.let { throw invalidValidationTestProbes(ENCODE_LABEL, it) }
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      Keys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_TEST_PROBES_CONTRACT_VERSION,
      Keys.TEST_PROBES to map(::probeWire),
    ),
  )
}

private const val ENCODE_LABEL = "validation-test-probes"

private fun probeWire(probe: ValidationTestProbe): Map<String, Any?> =
  mapOf(
    Keys.TEST_REF to probe.testRef,
    Keys.GUARDED_REF to probe.guardedRef,
    Keys.RESULT to probe.result.wireValue,
    Keys.REASON to probe.reason,
    Keys.REPAIRED to probe.repaired,
  )
