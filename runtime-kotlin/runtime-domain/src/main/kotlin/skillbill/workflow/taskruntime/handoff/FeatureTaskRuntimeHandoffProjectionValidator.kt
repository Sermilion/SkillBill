package skillbill.workflow.taskruntime.handoff

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeHandoffProjection
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionResult

object FeatureTaskRuntimeHandoffProjectionValidator {
  const val COMPACT_REFERENCE_MAX_LENGTH: Int = 512

  fun validate(inputs: FeatureTaskRuntimeHandoffProjectionInputs): FeatureTaskRuntimeHandoffEnvelope =
    when (val result = validateToResult(inputs)) {
      is FeatureTaskRuntimeHandoffProjectionResult.Accepted -> result.envelope
      is FeatureTaskRuntimeHandoffProjectionResult.Rejected ->
        throw invalidFeatureTaskRuntimeHandoffProjection(result.context)
    }

  fun validateToResult(inputs: FeatureTaskRuntimeHandoffProjectionInputs): FeatureTaskRuntimeHandoffProjectionResult {
    val declarationRejection =
      FeatureTaskRuntimeHandoffProjectionDeclarationChecks.rejectUnselectedStepOutputs(inputs)
        ?: FeatureTaskRuntimeHandoffProjectionDeclarationChecks.rejectDuplicateProjectionNames(inputs)
    declarationRejection?.let { return FeatureTaskRuntimeHandoffProjectionResult.Rejected(it) }
    val projections = mutableListOf<FeatureTaskRuntimeHandoffProjection>()
    for (declaration in inputs.declarations) {
      when (val result = resolveProjection(inputs, declaration)) {
        is FeatureTaskRuntimeHandoffProjectionStep.Rejected ->
          return FeatureTaskRuntimeHandoffProjectionResult.Rejected(result.context)
        is FeatureTaskRuntimeHandoffProjectionStep.Value -> result.value?.let(projections::add)
      }
    }
    return FeatureTaskRuntimeHandoffProjectionResult.Accepted(
      FeatureTaskRuntimeHandoffEnvelope(
        consumerPhaseId = inputs.consumerPhaseId,
        projections = projections,
        repositoryCheckpoint = inputs.resolvedCheckpoint,
      ),
    )
  }

  private fun resolveProjection(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionStep<FeatureTaskRuntimeHandoffProjection?> {
    val rejection =
      FeatureTaskRuntimeHandoffProjectionDeclarationChecks.requireSameConsumer(inputs, declaration)
        ?: FeatureTaskRuntimeHandoffProjectionDeclarationChecks.requireSupportedContractVersion(inputs, declaration)
    rejection?.let { return FeatureTaskRuntimeHandoffProjectionStep.Rejected(it) }
    val fields =
      when (val result = resolveCheckpointFields(inputs, declaration)) {
        is FeatureTaskRuntimeHandoffProjectionStep.Rejected -> return result
        is FeatureTaskRuntimeHandoffProjectionStep.Value -> result.value
      }
    return if (fields == null) {
      FeatureTaskRuntimeHandoffProjectionStep.Value(null)
    } else {
      val shapeRejection =
        FeatureTaskRuntimeHandoffProjectionDeclarationChecks.enforceDeclaredShape(inputs, declaration, fields)
          ?: FeatureTaskRuntimeHandoffProjectionDeclarationChecks.enforceCompactReferences(inputs, declaration, fields)
      shapeRejection?.let { FeatureTaskRuntimeHandoffProjectionStep.Rejected(it) }
        ?: FeatureTaskRuntimeHandoffProjectionStep.Value(
          FeatureTaskRuntimeHandoffProjection(
            projectionName = declaration.projectionName,
            sourceRef = declaration.sourceRef,
            projectionContractId = declaration.projectionContractId,
            projectionContractVersion = declaration.projectionContractVersion,
            promptVisibility = declaration.promptVisibility,
            fields = fields,
            producerIteration =
              FeatureTaskRuntimeHandoffProjectionFieldResolver.resolvedProducerIteration(inputs, declaration),
          ),
        )
    }
  }

  private fun resolveCheckpointFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionStep<List<FeatureTaskRuntimeHandoffProjectionField>?> {
    val resolvedFields =
      when (val result = FeatureTaskRuntimeHandoffProjectionFieldResolver.resolveFields(inputs, declaration)) {
        is FeatureTaskRuntimeHandoffProjectionStep.Rejected -> return result
        is FeatureTaskRuntimeHandoffProjectionStep.Value -> result.value
      }
    return when (
      val checkpoint =
        FeatureTaskRuntimeHandoffProjectionEnvelopeWire.enforceCheckpointPolicy(
          inputs,
          declaration,
          resolvedFields.orEmpty(),
        )
    ) {
      is FeatureTaskRuntimeHandoffProjectionStep.Rejected -> checkpoint
      is FeatureTaskRuntimeHandoffProjectionStep.Value ->
        FeatureTaskRuntimeHandoffProjectionStep.Value(checkpoint.value.takeIf { resolvedFields != null })
    }
  }

  fun privateEvidenceReference(
    producingPhaseId: String,
    iteration: Int,
  ): String = PRIVATE_EVIDENCE_LOCATOR_PREFIX + "$producingPhaseId#$iteration"

  const val CHECKPOINT_PRODUCER_CLAIM_SEPARATOR: String = "+producer-claimed:"

  const val PRIVATE_EVIDENCE_LOCATOR_PREFIX: String = "$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY/"
  const val PHASE_OUTPUT_RECEIPT_FIELD: String = "phase_output_receipt"
  const val CEREMONY_SCALING_FIELD: String = "ceremony_scaling"
  const val ADDON_CONTENT_FIELD: String = "addon_content"
}

internal fun rejectedFeatureTaskRuntimeHandoffProjectionContext(
  inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  declaration: PhaseHandoffProjectionDeclaration,
  failureKind: FeatureTaskRuntimeHandoffProjectionFailureKind,
  reason: String,
): InvalidFeatureTaskRuntimeHandoffProjectionContext =
  InvalidFeatureTaskRuntimeHandoffProjectionContext(
    workflowId = inputs.workflowId,
    consumerPhaseId = inputs.consumerPhaseId,
    projectionName = declaration.projectionName,
    projectionContractId = declaration.projectionContractId,
    projectionContractVersion = declaration.projectionContractVersion,
    failureKind = failureKind,
    reason = reason,
  )

internal sealed interface FeatureTaskRuntimeHandoffProjectionStep<out T> {
  data class Value<T>(val value: T) : FeatureTaskRuntimeHandoffProjectionStep<T>

  data class Rejected(val context: InvalidFeatureTaskRuntimeHandoffProjectionContext) :
    FeatureTaskRuntimeHandoffProjectionStep<Nothing>
}
