package skillbill.workflow.taskruntime.handoff

import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object FeatureTaskRuntimeHandoffProjectionDeclarationChecks {
  val supportedProjectionContractVersions: Set<String> = setOf("0.1", "0.2", "0.3")

  fun rejectUnselectedStepOutputs(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? {
    val declaration = inputs.declarations.firstOrNull() ?: return null
    inputs.unselectedStepIds.firstOrNull(inputs.resolvedUpstream.outputsByPhaseId::containsKey)?.let { stepId ->
      return rejectedFeatureTaskRuntimeHandoffProjectionContext(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "the run did not select step '$stepId', so it cannot carry a settled output from '$stepId'.",
      )
    }
    return null
  }

  fun rejectDuplicateProjectionNames(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? {
    val seen = mutableSetOf<String>()
    inputs.declarations.forEach { declaration ->
      if (!seen.add(declaration.projectionName)) {
        return rejectedFeatureTaskRuntimeHandoffProjectionContext(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.DUPLICATE_PROJECTION_NAME,
          "the consumer phase declares this projection name more than once.",
        )
      }
    }
    return null
  }

  fun requireSameConsumer(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? {
    if (declaration.consumerPhaseId != inputs.consumerPhaseId) {
      return rejectedFeatureTaskRuntimeHandoffProjectionContext(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "the declaration belongs to consumer phase '${declaration.consumerPhaseId}'.",
      )
    }
    return null
  }

  fun requireSupportedContractVersion(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? {
    if (declaration.projectionContractVersion !in supportedProjectionContractVersions) {
      return rejectedFeatureTaskRuntimeHandoffProjectionContext(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.UNSUPPORTED_CONTRACT_VERSION,
        "supported versions are ${supportedProjectionContractVersions.joinToString()}.",
      )
    }
    return null
  }

  fun enforceDeclaredShape(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? {
    val seen = mutableSetOf<String>()
    fields.forEach { field ->
      if (field.name !in declaration.declaredFieldNames ||
        field.name in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
      ) {
        return rejectedFeatureTaskRuntimeHandoffProjectionContext(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.UNDECLARED_FIELD,
          "field '${field.name}' is not part of the declared projection shape.",
        )
      }
      if (!seen.add(field.name)) {
        return rejectedFeatureTaskRuntimeHandoffProjectionContext(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "field '${field.name}' appears more than once.",
        )
      }
    }
    declaration.declaredFieldNames.forEach { declaredName ->
      if (declaredName !in seen && declaration.required && !optionalDeclaredField(declaration, declaredName)) {
        return rejectedFeatureTaskRuntimeHandoffProjectionContext(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
          "declared field '$declaredName' resolved to no value.",
        )
      }
    }
    return null
  }

  fun enforceCompactReferences(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    fields: List<FeatureTaskRuntimeHandoffProjectionField>,
  ): InvalidFeatureTaskRuntimeHandoffProjectionContext? {
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
        return rejectedFeatureTaskRuntimeHandoffProjectionContext(
          inputs,
          declaration,
          FeatureTaskRuntimeHandoffProjectionFailureKind.INVALID_COMPACT_REFERENCE,
          problem,
        )
      }
    }
    return null
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
