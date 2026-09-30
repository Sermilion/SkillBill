package skillbill.workflow.taskruntime.handoff

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object FeatureTaskRuntimeHandoffProjectionDeclarationChecks {
  val supportedProjectionContractVersions: Set<String> = setOf("0.1", "0.2", "0.3")

  fun rejectUnselectedStepOutputs(inputs: FeatureTaskRuntimeHandoffProjectionInputs) {
    val declaration = inputs.declarations.firstOrNull() ?: return
    inputs.unselectedStepIds.firstOrNull(inputs.resolvedUpstream.outputsByPhaseId::containsKey)?.let { stepId ->
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "the run did not select step '$stepId', so it cannot carry a settled output from '$stepId'.",
      )
    }
  }

  fun rejectDuplicateProjectionNames(inputs: FeatureTaskRuntimeHandoffProjectionInputs) {
    val seen = mutableSetOf<String>()
    inputs.declarations.forEach { declaration ->
      if (!seen.add(declaration.projectionName)) {
        rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.DUPLICATE_PROJECTION_NAME,
          "the consumer phase declares this projection name more than once.",
        )
      }
    }
  }

  fun requireSameConsumer(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ) {
    if (declaration.consumerPhaseId != inputs.consumerPhaseId) {
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "the declaration belongs to consumer phase '${declaration.consumerPhaseId}'.",
      )
    }
  }

  fun requireSupportedContractVersion(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ) {
    if (declaration.projectionContractVersion !in supportedProjectionContractVersions) {
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.UNSUPPORTED_CONTRACT_VERSION,
        "supported versions are ${supportedProjectionContractVersions.joinToString()}.",
      )
    }
  }

  fun enforceDeclaredShape(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ) {
    val seen = mutableSetOf<String>()
    fields.forEach { field ->
      if (field.name !in declaration.declaredFieldNames ||
        field.name in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
      ) {
        rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.UNDECLARED_FIELD,
          "field '${field.name}' is not part of the declared projection shape.",
        )
      }
      if (!seen.add(field.name)) {
        rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "field '${field.name}' appears more than once.",
        )
      }
    }
    declaration.declaredFieldNames.forEach { declaredName ->
      if (declaredName !in seen && declaration.required && !optionalDeclaredField(declaration, declaredName)) {
        rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "declared field '$declaredName' resolved to no value.",
        )
      }
    }
  }

  fun enforceCompactReferences(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ) {
    fields.forEach { field ->
      val reference = field.value as? FeatureTaskRuntimeHandoffProjectionValue.CompactReference ?: return@forEach
      val problem =
        when {
          reference.value.length > FeatureTaskRuntimeHandoffProjectionValidator.COMPACT_REFERENCE_MAX_LENGTH ->
            "reference in field '${field.name}' exceeds " +
              "${FeatureTaskRuntimeHandoffProjectionValidator.COMPACT_REFERENCE_MAX_LENGTH} characters; a compact " +
              "reference must be an identifier, not an inlined body."
          reference.value.any { it == '\n' || it == '\r' } ->
            "reference in field '${field.name}' contains a line break; a compact reference must be a single token."
          referencesPrivateEvidence(reference.value) && !declaration.allowsPrivateArtifactReference ->
            "field '${field.name}' references a private evidence artifact, but this projection does not declare a " +
              "runtime-owned deterministic inspection operation for it."
          else -> null
        }
      if (problem != null) {
        rejectFeatureTaskRuntimeHandoffProjection(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE,
          problem,
        )
      }
    }
  }

  private fun optionalDeclaredField(
    declaration: PhaseHandoffProjectionDeclaration,
    fieldName: String,
  ): Boolean =
    declaration.projectionContractId ==
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE &&
      fieldName == "directive"

  private fun referencesPrivateEvidence(referenceValue: String): Boolean =
    referenceValue.startsWith(FeatureTaskRuntimeHandoffProjectionValidator.PRIVATE_EVIDENCE_LOCATOR_PREFIX)
}
