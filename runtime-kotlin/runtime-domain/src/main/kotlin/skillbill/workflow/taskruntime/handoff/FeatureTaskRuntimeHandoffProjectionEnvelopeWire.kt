package skillbill.workflow.taskruntime.handoff

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.REPOSITORY_CHECKPOINT_FIELD

internal object FeatureTaskRuntimeHandoffProjectionEnvelopeWire {
  const val REPOSITORY_CHECKPOINT_FIELD: String = "repository_checkpoint"

  fun enforceCheckpointPolicy(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ): List<FeatureTaskRuntimeHandoffProjectionField> {
    val carried = receiptCarriedCheckpointFingerprint(fields)
    checkpointPolicyViolation(inputs, declaration)?.let { violation ->
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.CHECKPOINT_POLICY_VIOLATION,
        violation,
      )
    }
    if (declaration.checkpointPolicy == FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED) {
      return fields
    }
    val resolvedFingerprint = inputs.resolvedCheckpoint?.fingerprint ?: return fields
    val refreshed =
      fields.map { field ->
        resolvedCheckpointField(field, resolvedFingerprint, carried)
      }
    if (
      REPOSITORY_CHECKPOINT_FIELD in declaration.declaredFieldNames &&
      refreshed.none { it.name == REPOSITORY_CHECKPOINT_FIELD }
    ) {
      return refreshed +
        FeatureTaskRuntimeHandoffProjectionField(
          REPOSITORY_CHECKPOINT_FIELD,
          FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
            kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
            value = resolvedFingerprint,
          ),
        )
    }
    return refreshed
  }

  private fun checkpointPolicyViolation(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): String? =
    when (declaration.checkpointPolicy) {
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED -> null
      FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY ->
        if (inputs.resolvedCheckpoint == null) {
          "checkpoint-aware policy requires a freshly resolved repository checkpoint, none was supplied."
        } else {
          null
        }
      FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH ->
        if (inputs.resolvedCheckpoint == null) {
          "must_match requires a freshly resolved repository checkpoint, none was supplied."
        } else {
          null
        }
    }

  private fun resolvedCheckpointField(
    field: FeatureTaskRuntimeHandoffProjectionField,
    resolvedFingerprint: String,
    carriedFingerprint: String?,
  ): FeatureTaskRuntimeHandoffProjectionField =
    if (field.name == REPOSITORY_CHECKPOINT_FIELD) {
      field.copy(
        value =
          FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
            kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
            value =
              resolvedFingerprint +
                (
                  carriedFingerprint?.let {
                    FeatureTaskRuntimeHandoffProjectionValidator.CHECKPOINT_PRODUCER_CLAIM_SEPARATOR + it
                  }.orEmpty()
                ),
          ),
      )
    } else {
      field
    }

  private fun receiptCarriedCheckpointFingerprint(fields: List<FeatureTaskRuntimeHandoffProjectionField>): String? =
    fields.firstOrNull { it.name == REPOSITORY_CHECKPOINT_FIELD }
      ?.value
      ?.let { it as? FeatureTaskRuntimeHandoffProjectionValue.CompactReference }
      ?.takeIf { it.kind == FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT }
      ?.value
      ?.substringAfter(FeatureTaskRuntimeHandoffProjectionValidator.CHECKPOINT_PRODUCER_CLAIM_SEPARATOR)
      ?.takeIf(String::isNotBlank)
}
