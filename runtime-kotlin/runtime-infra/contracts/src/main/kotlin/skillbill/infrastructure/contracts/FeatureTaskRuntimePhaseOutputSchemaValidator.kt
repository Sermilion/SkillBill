package skillbill.infrastructure.contracts

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeBuildReceiptSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.contracts.phaseoutput.FeatureTaskRuntimePhaseOutputStructuralRepair
import skillbill.infrastructure.contracts.phaseoutput.FeatureTaskRuntimePhaseOutputStructuralRepairDecision
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeBuildReceiptSchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimePhaseOutputWireSchema
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.phase.requireAccepted
import skillbill.workflow.taskruntime.phase.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class FeatureTaskRuntimePhaseOutputSchemaValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult {
    val decision = FeatureTaskRuntimePhaseOutputStructuralRepair.inspect(phaseOutputText, sourceLabel)
    return when (decision) {
      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected ->
        leniently(phaseOutputText, sourceLabel)
          ?.let { FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(it) }
          ?: synthesizeProse(phaseOutputText, sourceLabel)
          ?: FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
            code = decision.code,
            reason = decision.reason,
            sourceLocation = decision.sourceLocation,
          )

      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted ->
        try {
          val normalized =
            if (sourceLabel in LENIENT_VERIFYING_PHASE_OUTPUT_SCHEMA) {
              FeatureTaskRuntimePhaseOutputWireSchema.normalizeVerifyingPhaseOutputLenient(
                decision.text,
                sourceLabel,
              )
            } else {
              FeatureTaskRuntimePhaseOutputWireSchema.normalizePhaseOutput(
                decision.text,
                sourceLabel,
              )
            }
          validateNestedBuildReceipt(normalized, sourceLabel)
          if (decision.evidence == null) {
            FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(normalized)
          } else {
            FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(normalized, decision.evidence)
          }
        } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
          synthesizeProse(phaseOutputText, sourceLabel)
            ?: FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
              code = FeatureTaskRuntimePhaseOutputFailureCode.fromWire(error.failureCode),
              reason = error.payloadFreeReason ?: "Phase output failed the phase-specific schema contract.",
              diagnosticReason = error.reason,
              payloadFreeReason = error.payloadFreeReason,
              structuralRepairEvidence = decision.evidence,
            )
        } catch (error: InvalidFeatureTaskRuntimeBuildReceiptSchemaError) {
          FeatureTaskRuntimePhaseOutputValidationResult.Rejected(
            code = FeatureTaskRuntimePhaseOutputFailureCode.fromWire(error.failureCode),
            reason = error.payloadFreeReason ?: "Build receipt failed the build-receipt schema contract.",
            diagnosticReason = error.reason,
            payloadFreeReason = error.payloadFreeReason,
            structuralRepairEvidence = decision.evidence,
          )
        }
    }
  }

  private fun synthesizeProse(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult? {
    if (!ProsePhaseOutputSynthesizer.isProsePhase(sourceLabel)) return null
    val envelope = ProsePhaseOutputSynthesizer.trySynthesize(phaseOutputText, sourceLabel) ?: return null
    return try {
      val canonical =
        JsonCodec.mapToJsonString(
          JsonCodec.anyToStringAnyMap(envelope) ?: emptyMap(),
        )
      val normalized = FeatureTaskRuntimePhaseOutputWireSchema.normalizePhaseOutput(canonical, sourceLabel)
      FeatureTaskRuntimePhaseOutputValidationResult.AcceptedUnchanged(normalized)
    } catch (_: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      null
    }
  }

  private fun leniently(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput? {
    if (sourceLabel !in LENIENT_VERIFYING_PHASE_OUTPUT_SCHEMA) return null
    return try {
      val normalized =
        FeatureTaskRuntimePhaseOutputWireSchema.normalizeVerifyingPhaseOutputLenient(
          phaseOutputText,
          sourceLabel,
        )
      validateNestedBuildReceipt(normalized, sourceLabel)
      normalized
    } catch (_: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      null
    } catch (_: InvalidFeatureTaskRuntimeBuildReceiptSchemaError) {
      null
    }
  }

  override fun validatePhaseOutputText(
    phaseOutputText: String,
    sourceLabel: String,
  ) {
    validatePhaseOutput(phaseOutputText, sourceLabel).requireAccepted(sourceLabel)
  }

  override fun normalizePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput =
    validatePhaseOutput(phaseOutputText, sourceLabel).requireAccepted(sourceLabel)

  private fun validateNestedBuildReceipt(
    normalized: NormalizedFeatureTaskRuntimePhaseOutput,
    sourceLabel: String,
  ) {
    if (sourceLabel != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) return
    val envelope = JsonCodec.anyToStringAnyMap(normalized.envelopePayload()) ?: emptyMap()
    val produced =
      JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
        ?: throw InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
          sourceLabel = sourceLabel,
          reason = "produced_outputs must be present for the build phase envelope.",
          payloadFreeReason = "produced_outputs must be present for the build phase envelope.",
        )
    val buildReceipt =
      JsonCodec.anyToStringAnyMap(produced["build_receipt"])
        ?: throw InvalidFeatureTaskRuntimeBuildReceiptSchemaError(
          sourceLabel = sourceLabel,
          reason = "produced_outputs.build_receipt is required for the build phase envelope.",
          payloadFreeReason = "produced_outputs.build_receipt is required for the build phase envelope.",
        )
    FeatureTaskRuntimeBuildReceiptSchemaValidator.validate(buildReceipt, sourceLabel)
  }

  private companion object {
    val LENIENT_VERIFYING_PHASE_OUTPUT_SCHEMA: Set<String> =
      setOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
      )
  }
}
