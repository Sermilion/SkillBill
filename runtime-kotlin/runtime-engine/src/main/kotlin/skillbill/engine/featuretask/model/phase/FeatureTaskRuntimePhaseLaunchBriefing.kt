package skillbill.engine.featuretask.model.phase

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_LAUNCH_BRIEFING_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeHandoffEnvelopeFromArtifact
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.phaseartifacts.schemaError

data class FeatureTaskRuntimePhaseLaunchBriefing(
  val phaseId: String,
  val specReference: String,
  val featureSize: String,
  val acceptanceCriteria: List<String>,
  val mandatesAndOverrides: List<String>,
  val handoffEnvelope: FeatureTaskRuntimeHandoffEnvelope,
  val derivedContextKeys: List<String>,
  val briefingText: String,
  val drivingVerdict: String? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseLaunchBriefing.phaseId must be non-blank." }
    require(specReference.isNotBlank()) {
      "FeatureTaskRuntimePhaseLaunchBriefing.specReference must be non-blank; run-invariants are unconditional."
    }
    require(featureSize.isNotBlank()) {
      "FeatureTaskRuntimePhaseLaunchBriefing.featureSize must be non-blank; run-invariants are unconditional."
    }
    require(acceptanceCriteria.isNotEmpty()) {
      "FeatureTaskRuntimePhaseLaunchBriefing.acceptanceCriteria must be non-empty; run-invariants are unconditional."
    }
    require(briefingText.isNotBlank()) { "FeatureTaskRuntimePhaseLaunchBriefing.briefingText must be non-blank." }
  }

  fun asBriefingArtifactEntry(): Any = briefingArtifactWireMap()

  internal fun briefingArtifactWireMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to CONTRACT_VERSION,
      SharedPayloadKeys.PHASE_ID to phaseId,
      "spec_reference" to specReference,
      "feature_size" to featureSize,
      "acceptance_criteria" to acceptanceCriteria,
      "mandates_and_overrides" to mandatesAndOverrides,
      "handoff_envelope" to handoffEnvelope.asWorkflowArtifactEntry(),
      "derived_context_keys" to derivedContextKeys,
      "briefing_text" to briefingText,
    ).let { base ->
      LinkedHashMap(base).apply {
        drivingVerdict?.let { put("driving_verdict", it) }
      }
    }

  companion object {
    internal fun fromBriefingArtifactWire(raw: Map<String, Any?>): FeatureTaskRuntimePhaseLaunchBriefing {
      val unknownFields = raw.keys - ALLOWED_FIELDS - FeatureTaskRuntimeHandoffSourceRef.RETIRED_PRIOR_GAP_MEMORY_WIRE
      if (unknownFields.isNotEmpty()) {
        schemaError(
          "Feature-task-runtime briefing artifact contains unsupported fields " +
            "${unknownFields.sorted().joinToString()}. Restart this workflow or migrate the durable row " +
            "to briefing contract $CONTRACT_VERSION before retrying.",
        )
      }
      val version = raw[SharedPayloadKeys.CONTRACT_VERSION]
      if (version != CONTRACT_VERSION) {
        schemaError(
          "Feature-task-runtime briefing artifact contract_version must be '$CONTRACT_VERSION', was " +
            "'${version ?: "missing"}'. Restart this workflow or migrate the durable row before retrying.",
        )
      }
      if (raw.containsKey("upstream_outputs_by_phase_id")) {
        schemaError(
          "Feature-task-runtime briefing artifact carries the removed 'upstream_outputs_by_phase_id' payload map. " +
            "Delivered handoffs are now validated projections under 'handoff_envelope'; this durable row must be " +
            "migrated or deleted out of band rather than silently reinterpreted.",
        )
      }
      return FeatureTaskRuntimePhaseLaunchBriefing(
        phaseId = raw.requireStringField(SharedPayloadKeys.PHASE_ID),
        specReference = raw.requireStringField("spec_reference"),
        featureSize = raw.requireStringField("feature_size"),
        acceptanceCriteria = raw.requireStringListField("acceptance_criteria"),
        mandatesAndOverrides = raw.requireStringListField("mandates_and_overrides"),
        handoffEnvelope = raw.requireEnvelopeField("handoff_envelope"),
        derivedContextKeys = raw.requireStringListField("derived_context_keys"),
        briefingText = raw.requireStringField("briefing_text"),
        drivingVerdict = raw.optionalStringField("driving_verdict"),
      )
    }

    private fun schemaError(detail: String): Nothing = throw invalidWorkflowStateSchemaError(detail)

    private fun Map<String, Any?>.requireEnvelopeField(key: String): FeatureTaskRuntimeHandoffEnvelope {
      val rawValue = if (containsKey(key)) this[key] else schemaError(missingMessage(key, "object"))
      val envelope =
        JsonCodec.anyToStringAnyMap(rawValue)
          ?: schemaError("Feature-task-runtime briefing artifact field '$key' must decode to an object.")
      return requireNotNull(decodeHandoffEnvelopeFromArtifact(envelope))
    }

    private fun Map<String, Any?>.requireStringField(key: String): String {
      val value = this[key] ?: schemaError("Feature-task-runtime briefing artifact map is missing field '$key'.")
      return (value as? String)?.takeIf(String::isNotBlank)
        ?: schemaError("Feature-task-runtime briefing artifact field '$key' must decode to a non-blank string.")
    }

    private fun Map<String, Any?>.optionalStringField(key: String): String? {
      if (!containsKey(key) || this[key] == null) {
        return null
      }
      return (this[key] as? String)?.takeIf(String::isNotBlank)
        ?: schemaError(
          "Feature-task-runtime briefing artifact field '$key' must decode to a non-blank string when present.",
        )
    }

    private fun Map<String, Any?>.requireStringListField(key: String): List<String> {
      val list =
        (if (containsKey(key)) this[key] else schemaError(missingMessage(key, "list"))) as? List<*>
          ?: schemaError("Feature-task-runtime briefing artifact field '$key' must decode to a list.")
      return list.map { element ->
        element as? String ?: schemaError("Feature-task-runtime briefing artifact field '$key' must contain strings.")
      }
    }

    private fun missingMessage(
      key: String,
      kind: String,
    ): String = "Feature-task-runtime briefing artifact map is missing required $kind field '$key'."

    const val CONTRACT_VERSION: String = FEATURE_TASK_RUNTIME_PHASE_LAUNCH_BRIEFING_CONTRACT_VERSION

    private val ALLOWED_FIELDS: Set<String> =
      setOf(
        SharedPayloadKeys.CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID,
        "spec_reference",
        "feature_size",
        "acceptance_criteria",
        "mandates_and_overrides",
        "handoff_envelope",
        "derived_context_keys",
        "briefing_text",
        "driving_verdict",
        "durably_closed_criterion_refs",
      )
  }
}
