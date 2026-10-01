package skillbill.engine.featuretask.validation

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeValidationEvidenceFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence

internal object RuntimeGateRecordIntegrity {
  fun requireIntact(
    normalized: NormalizedFeatureTaskRuntimePhaseOutput,
    phaseId: String,
  ) {
    val envelope = normalized.envelopeWireMap()
    val produced =
      envelope
        .takeIf { it[SharedPayloadKeys.STATUS] == WorkflowStepStatus.COMPLETED.wireValue }
        ?.let { JsonCodec.anyToStringAnyMap(it[SharedPayloadKeys.PRODUCED_OUTPUTS]) }
        ?: return
    try {
      if (produced.containsKey(ValidationEvidencePayloadKeys.BUILD_RECEIPT)) {
        requireBuildReceipt(receipt(produced, ValidationEvidencePayloadKeys.BUILD_RECEIPT, phaseId), phaseId)
      }
      if (produced.containsKey(ValidationEvidencePayloadKeys.VALIDATION_RESULT)) {
        requireValidationResult(receipt(produced, ValidationEvidencePayloadKeys.VALIDATION_RESULT, phaseId), phaseId)
      }
    } catch (error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(phaseId, error.reason, error)
    }
  }

  private fun receipt(
    produced: Map<String, Any?>,
    key: String,
    phaseId: String,
  ): Map<String, Any?> = JsonCodec.anyToStringAnyMap(produced[key]) ?: invalid(phaseId, "$key must be a mapping.")

  private fun requireBuildReceipt(
    receipt: Map<String, Any?>,
    phaseId: String,
  ) {
    if (receipt[SharedPayloadKeys.CONTRACT_VERSION] != FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION) {
      invalid(phaseId, "Unsupported build receipt contract_version.")
    }
    requirePassed(phaseId, decodeValidationGateExecutionEvidenceFromArtifact(receipt, phaseId))
  }

  private fun requireValidationResult(
    result: Map<String, Any?>,
    phaseId: String,
  ) {
    val runs = requirePassed(phaseId, decodeValidationGateExecutionEvidenceFromArtifact(result, phaseId))
    val commands =
      decodeValidationEvidenceFromArtifact(result[ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE], phaseId)
        ?: invalid(phaseId, "Command evidence is missing.")
    commands.requireSuccessfulResult(phaseId)
    if (commands.results.size != runs.gateRuns.size ||
      commands.results.zip(runs.gateRuns).any { (command, run) ->
        command.command != run.command || command.exitCode != run.exitCode
      }
    ) {
      invalid(phaseId, "Command results differ from gate runs.")
    }
  }

  private fun requirePassed(
    phaseId: String,
    evidence: FeatureTaskRuntimeValidationGateExecutionEvidence?,
  ): FeatureTaskRuntimeValidationGateExecutionEvidence {
    val runs = evidence ?: invalid(phaseId, "Gate execution evidence is missing.")
    if (runs.validationStatus != PASSED) invalid(phaseId, "Completed gate evidence must have passed status.")
    return runs
  }

  private fun invalid(
    phaseId: String,
    reason: String,
  ): Nothing = throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(phaseId, reason)

  private const val PASSED = "passed"
}
