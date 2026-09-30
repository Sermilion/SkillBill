package skillbill.workflow.taskruntime.handoff

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjection
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.persistence.task.runtime.store.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY

object FeatureTaskRuntimeHandoffProjectionValidator {
  const val COMPACT_REFERENCE_MAX_LENGTH: Int = 512

  fun validate(inputs: FeatureTaskRuntimeHandoffProjectionInputs): FeatureTaskRuntimeHandoffEnvelope {
    FeatureTaskRuntimeHandoffProjectionDeclarationChecks.rejectUnselectedStepOutputs(inputs)
    FeatureTaskRuntimeHandoffProjectionDeclarationChecks.rejectDuplicateProjectionNames(inputs)
    val projections =
      inputs.declarations.mapNotNull { declaration ->
        FeatureTaskRuntimeHandoffProjectionDeclarationChecks.requireSameConsumer(inputs, declaration)
        FeatureTaskRuntimeHandoffProjectionDeclarationChecks.requireSupportedContractVersion(inputs, declaration)
        val resolved = FeatureTaskRuntimeHandoffProjectionFieldResolver.resolveFields(inputs, declaration)
        val fields =
          FeatureTaskRuntimeHandoffProjectionEnvelopeWire.enforceCheckpointPolicy(
            inputs,
            declaration,
            resolved.orEmpty(),
          )
        if (resolved == null) return@mapNotNull null
        FeatureTaskRuntimeHandoffProjectionDeclarationChecks.enforceDeclaredShape(inputs, declaration, fields)
        FeatureTaskRuntimeHandoffProjectionDeclarationChecks.enforceCompactReferences(inputs, declaration, fields)
        FeatureTaskRuntimeHandoffProjection(
          projectionName = declaration.projectionName,
          sourceRef = declaration.sourceRef,
          projectionContractId = declaration.projectionContractId,
          projectionContractVersion = declaration.projectionContractVersion,
          promptVisibility = declaration.promptVisibility,
          fields = fields,
          producerIteration =
            FeatureTaskRuntimeHandoffProjectionFieldResolver.resolvedProducerIteration(
              inputs,
              declaration,
            ),
        )
      }
    return FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = inputs.consumerPhaseId,
      projections = projections,
      repositoryCheckpoint = inputs.resolvedCheckpoint,
    )
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

internal fun rejectFeatureTaskRuntimeHandoffProjection(
  inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  declaration: PhaseHandoffProjectionDeclaration,
  failureKind: FeatureTaskRuntimeHandoffProjectionFailureKind,
  reason: String,
): Nothing =
  throw InvalidFeatureTaskRuntimeHandoffProjectionError(
    context =
      InvalidFeatureTaskRuntimeHandoffProjectionContext(
        workflowId = inputs.workflowId,
        consumerPhaseId = inputs.consumerPhaseId,
        projectionName = declaration.projectionName,
        projectionContractId = declaration.projectionContractId,
        projectionContractVersion = declaration.projectionContractVersion,
        failureKind = failureKind,
        reason = reason,
      ),
  )
