package skillbill.infrastructure.contracts

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeValidationEvidenceSchemaValidator
import skillbill.workflow.taskruntime.artifact.decodeValidationEvidenceFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeValidationGateExecutionEvidenceFromArtifact
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal fun validateNestedGateEvidence(
  normalized: NormalizedFeatureTaskRuntimePhaseOutput,
  sourceLabel: String,
) {
  val envelope = JsonCodec.anyToStringAnyMap(normalized.envelopePayload()) ?: return
  if (envelope[SharedPayloadKeys.STATUS] != "completed") return
  val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]) ?: return
  if (!produced.containsKey(ValidationEvidencePayloadKeys.VALIDATION_RESULT)) return
  val result =
    JsonCodec.anyToStringAnyMap(produced[ValidationEvidencePayloadKeys.VALIDATION_RESULT])
      ?: invalidGateEvidence(sourceLabel, "validation_result must be a mapping.")
  val runs =
    decodeValidationGateExecutionEvidenceFromArtifact(result, sourceLabel)
      ?: invalidGateEvidence(sourceLabel, "Gate execution evidence is missing.")
  if (runs.validationStatus != "passed") {
    throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
      sourceLabel,
      "Completed gate evidence must have passed status.",
    )
  }
  val commandMap =
    JsonCodec.anyToStringAnyMap(result[ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE])
      ?: invalidGateEvidence(sourceLabel, "Command evidence is missing.")
  FeatureTaskRuntimeValidationEvidenceSchemaValidator.validate(commandMap, sourceLabel)
  val commands =
    decodeValidationEvidenceFromArtifact(commandMap, sourceLabel)
      ?: invalidGateEvidence(sourceLabel, "Command evidence is missing.")
  commands.requireSuccessfulResult(sourceLabel)
  if (commands.results.size != runs.gateRuns.size ||
    commands.results.zip(runs.gateRuns).any { (command, run) ->
      command.command != run.command || command.exitCode != run.exitCode
    }
  ) {
    invalidGateEvidence(sourceLabel, "Command results differ from gate runs.")
  }
}

private fun invalidGateEvidence(
  sourceLabel: String,
  reason: String,
): Nothing = throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(sourceLabel, reason)
