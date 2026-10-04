package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.workflow.taskruntime.model.core.unrecognizedHandoffWireValue

sealed interface FeatureTaskRuntimeHandoffSourceRef {
  val wireValue: String

  data class UpstreamPhaseOutput(val producingPhaseId: String) : FeatureTaskRuntimeHandoffSourceRef {
    init {
      require(producingPhaseId.isNotBlank()) {
        "FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput.producingPhaseId must be non-blank."
      }
    }

    override val wireValue: String get() = "$UPSTREAM_PHASE_OUTPUT_PREFIX$producingPhaseId"
  }

  data class RunInvariantField(val invariantField: FeatureTaskRuntimeRunInvariantPromptField) :
    FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = "$RUN_INVARIANT_FIELD_PREFIX${invariantField.wireValue}"
  }

  object DerivedCeremonyScaling : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = DERIVED_CEREMONY_SCALING_WIRE
  }

  object SharedReviewEvidence : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = SHARED_REVIEW_EVIDENCE_WIRE
  }

  object RepairLedger : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = REPAIR_LEDGER_WIRE
  }

  data class AddonContentRef(val slug: String) : FeatureTaskRuntimeHandoffSourceRef {
    init {
      require(slug.isNotBlank()) { "FeatureTaskRuntimeHandoffSourceRef.AddonContentRef.slug must be non-blank." }
    }

    override val wireValue: String get() = "$ADDON_CONTENT_PREFIX$slug"
  }

  companion object {
    const val UPSTREAM_PHASE_OUTPUT_PREFIX: String = "upstream_phase_output:"
    const val RUN_INVARIANT_FIELD_PREFIX: String = "run_invariant_field:"
    const val ADDON_CONTENT_PREFIX: String = "addon_content:"
    const val DERIVED_CEREMONY_SCALING_WIRE: String = "derived_ceremony_scaling"
    const val SHARED_REVIEW_EVIDENCE_WIRE: String = "shared_review_evidence"
    const val REPAIR_LEDGER_WIRE: String = "repair_ledger"
    const val RETIRED_PRIOR_GAP_MEMORY_WIRE: String = "prior_gap_memory"

    fun fromWire(value: String): FeatureTaskRuntimeHandoffSourceRef =
      when {
        value == DERIVED_CEREMONY_SCALING_WIRE -> DerivedCeremonyScaling
        value == SHARED_REVIEW_EVIDENCE_WIRE -> SharedReviewEvidence
        value == REPAIR_LEDGER_WIRE -> RepairLedger
        value == RETIRED_PRIOR_GAP_MEMORY_WIRE -> unrecognizedHandoffWireValue("source ref", value)
        value.startsWith(UPSTREAM_PHASE_OUTPUT_PREFIX) ->
          UpstreamPhaseOutput(value.removePrefix(UPSTREAM_PHASE_OUTPUT_PREFIX))
        value.startsWith(RUN_INVARIANT_FIELD_PREFIX) ->
          RunInvariantField(
            FeatureTaskRuntimeRunInvariantPromptField.fromWire(value.removePrefix(RUN_INVARIANT_FIELD_PREFIX)),
          )
        value.startsWith(ADDON_CONTENT_PREFIX) -> AddonContentRef(value.removePrefix(ADDON_CONTENT_PREFIX))
        else -> unrecognizedHandoffWireValue("source ref", value)
      }
  }

  fun toDeclarationMap(): Map<String, String> =
    when (this) {
      is UpstreamPhaseOutput ->
        mapOf(
          "kind" to "upstream_phase_output",
          DecompositionPlanningPayloadKeys.ID to producingPhaseId,
        )
      is RunInvariantField ->
        mapOf(
          "kind" to "run_invariant_field",
          DecompositionPlanningPayloadKeys.ID to invariantField.wireValue,
        )
      DerivedCeremonyScaling ->
        mapOf(
          "kind" to "derived_ceremony_scaling",
          DecompositionPlanningPayloadKeys.ID to "ceremony_scaling",
        )
      SharedReviewEvidence ->
        mapOf(
          "kind" to SHARED_REVIEW_EVIDENCE_WIRE,
          DecompositionPlanningPayloadKeys.ID to SHARED_REVIEW_EVIDENCE_WIRE,
        )
      RepairLedger -> mapOf("kind" to REPAIR_LEDGER_WIRE, DecompositionPlanningPayloadKeys.ID to REPAIR_LEDGER_WIRE)
      is AddonContentRef -> mapOf("kind" to "addon_content", DecompositionPlanningPayloadKeys.ID to slug)
    }
}
