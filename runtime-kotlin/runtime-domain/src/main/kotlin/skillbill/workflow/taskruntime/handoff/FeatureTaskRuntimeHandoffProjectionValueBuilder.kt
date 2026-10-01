package skillbill.workflow.taskruntime.handoff

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.error.featuretask.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.REPOSITORY_CHECKPOINT_FIELD
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object FeatureTaskRuntimeHandoffProjectionValueBuilder {
  private const val DIRECTIVE_FIELD: String = "directive"

  private val phaseProjectionContractIds: Set<String> =
    setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_CLEARANCE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REPAIR_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
    )

  fun phaseProjectionFields(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    output: FeatureTaskRuntimePhaseOutput,
  ): List<FeatureTaskRuntimeHandoffProjectionField>? {
    if (declaration.projectionContractId !in phaseProjectionContractIds) return null
    val values = proseValues(inputs, declaration, output.output) + runtimeOwnedValues(inputs, declaration, output)
    return declaration.declaredFieldNames.mapNotNull { name ->
      values[name]?.let {
        FeatureTaskRuntimeHandoffProjectionField(name, projectionValue(name, it, inputs, declaration))
      }
    }
  }

  private fun proseValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    output: PhaseOutput,
  ): Map<String, Any?> {
    val isProse =
      declaration.projectionContractId == FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE
    if (isProse && output.value.isBlank()) {
      rejectFeatureTaskRuntimeHandoffProjection(
        inputs,
        declaration,
        FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
        "upstream phase output must contain non-blank prose for phase handoff.",
      )
    }
    return mapOf(
      SharedPayloadKeys.VALUE to output.value.takeIf(String::isNotBlank),
      DIRECTIVE_FIELD to output.prompt?.takeIf(String::isNotBlank),
    )
  }

  private fun runtimeOwnedValues(
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
    output: FeatureTaskRuntimePhaseOutput,
  ): Map<String, Any?> =
    when (declaration.projectionContractId) {
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_DISPOSITIONS,
      -> mapOf(REPOSITORY_CHECKPOINT_FIELD to checkpointFingerprint(inputs))
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT ->
        mapOf(
          "changed_paths" to inputs.resolvedCheckpoint?.workingTreeOwnedPaths.orEmpty(),
          REPOSITORY_CHECKPOINT_FIELD to checkpointFingerprint(inputs),
        )
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT ->
        measuredHistoryFacts(output)
      else -> FeatureTaskRuntimeHandoffProjectionFinalization.finalizationProjectionValues(inputs, declaration)
    }.filterValues { it != null }

  private fun measuredHistoryFacts(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?> {
    val produced =
      JsonCodec.anyToStringAnyMap(output.normalizedOutput?.runtimeRecord?.get(SharedPayloadKeys.PRODUCED_OUTPUTS))
        .orEmpty()
    val measured = JsonCodec.anyToStringAnyMap(produced[FeatureTaskRuntimeMeasuredFactKeys.MEASURED_FACTS]).orEmpty()
    return listOf(
      FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS,
      FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN,
      FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED,
    ).associateWith { key -> measured[key] ?: FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN }
  }

  private fun checkpointFingerprint(inputs: FeatureTaskRuntimeHandoffProjectionInputs): Map<String, String>? =
    inputs.resolvedCheckpoint?.let {
      mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to it.fingerprint)
    }

  private fun projectionValue(
    name: String,
    value: Any,
    inputs: FeatureTaskRuntimeHandoffProjectionInputs,
    declaration: PhaseHandoffProjectionDeclaration,
  ): FeatureTaskRuntimeHandoffProjectionValue {
    if (name == FeatureTaskRuntimeHandoffProjectionEnvelopeWire.REPOSITORY_CHECKPOINT_FIELD) {
      val checkpoint = JsonCodec.anyToStringAnyMap(value)
      val fingerprint =
        (checkpoint?.get(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT) as? String)
          ?.takeIf(String::isNotBlank)
          ?: rejectFeatureTaskRuntimeHandoffProjection(
            inputs,
            declaration,
            FeatureTaskRuntimeHandoffProjectionFailureKind.MALFORMED_FIELD,
            "repository_checkpoint must contain a non-blank fingerprint.",
          )
      return FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        fingerprint,
      )
    }
    return when (value) {
      is Iterable<*> ->
        FeatureTaskRuntimeHandoffProjectionValue.TextList(
          value.map { item ->
            when (item) {
              is String -> item
              is Map<*, *> ->
                JsonCodec.mapToJsonString(
                  item.entries.associate { (key, entryValue) -> key.toString() to entryValue },
                )
              else -> item.toString()
            }
          },
        )
      is Map<*, *> ->
        FeatureTaskRuntimeHandoffProjectionValue.Text(
          JsonCodec.mapToJsonString(value.entries.associate { (key, entryValue) -> key.toString() to entryValue }),
        )
      else -> FeatureTaskRuntimeHandoffProjectionValue.Text(value.toString())
    }
  }
}
