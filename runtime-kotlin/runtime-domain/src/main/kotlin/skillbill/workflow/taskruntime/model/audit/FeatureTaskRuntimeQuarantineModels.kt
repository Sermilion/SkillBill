package skillbill.workflow.taskruntime.model.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.model.core.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE

private const val FEATURE_TASK_RUNTIME_QUARANTINE_ARTIFACT_CONTRACT_VERSION: String = "0.3"

const val QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION: String = "planning_projection_schema"
private const val QUARANTINE_REJECTION_CLASS_HANDOFF_ENVELOPE: String = "handoff_envelope_schema"
const val QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION: String = "checkpoint_identity_contract_version"

private val QUARANTINE_REJECTION_CLASSES: Set<String> =
  setOf(
    QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION,
    QUARANTINE_REJECTION_CLASS_HANDOFF_ENVELOPE,
    QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION,
  )

private val QUARANTINE_ENVELOPE_FIELDS: Set<String> = setOf(SharedPayloadKeys.CONTRACT_VERSION, "entries")

data class FeatureTaskRuntimeQuarantineEntry(
  val producingPhaseId: String,
  val consumingPhaseId: String,
  val producingIteration: Int,
  val rejectionClass: String,
  val rejectionDetail: String,
  val regenerationAttempt: Int,
  val quarantinedAtIteration: Int,
  val diagnosticIdentity: String?,
  val rejectedRecordByteSize: Long,
  val rejectedRecordSha256: String,
  val diagnosticDegraded: Boolean = false,
) {
  init {
    val reason =
      productionViolation(
        producingPhaseId,
        consumingPhaseId,
        producingIteration,
        rejectionClass,
        rejectionDetail,
      ) ?: regenerationViolation(regenerationAttempt, quarantinedAtIteration)
        ?: diagnosticViolation(diagnosticIdentity, rejectedRecordByteSize, rejectedRecordSha256, diagnosticDegraded)
    require(reason == null) { reason.orEmpty() }
  }

  internal fun toArtifactMap(): Map<String, Any?> {
    val map =
      linkedMapOf<String, Any?>(
        "producing_phase_id" to producingPhaseId,
        "consuming_phase_id" to consumingPhaseId,
        "producing_iteration" to producingIteration,
        "rejection_class" to rejectionClass,
        "rejection_detail" to rejectionDetail,
        "regeneration_attempt" to regenerationAttempt,
        "quarantined_at_iteration" to quarantinedAtIteration,
      )
    if (diagnosticDegraded) {
      map["diagnostic_degraded"] = true
    } else {
      map["diagnostic_identity"] = requireNotNull(diagnosticIdentity)
    }
    map["rejected_record_byte_size"] = rejectedRecordByteSize
    map["rejected_record_sha256"] = rejectedRecordSha256
    return map
  }

  fun recordIdentifier(): String = "$producingPhaseId#$producingIteration"

  companion object {
    private fun productionViolation(
      producingPhaseId: String,
      consumingPhaseId: String,
      producingIteration: Int,
      rejectionClass: String,
      rejectionDetail: String,
    ): String? =
      when {
        producingPhaseId.isBlank() -> "FeatureTaskRuntimeQuarantineEntry.producingPhaseId must be non-blank."
        consumingPhaseId.isBlank() -> "FeatureTaskRuntimeQuarantineEntry.consumingPhaseId must be non-blank."
        producingIteration < 1 -> "FeatureTaskRuntimeQuarantineEntry.producingIteration must be >= 1."
        rejectionClass !in QUARANTINE_REJECTION_CLASSES ->
          "FeatureTaskRuntimeQuarantineEntry.rejectionClass must be a declared class."
        rejectionDetail.isBlank() -> "FeatureTaskRuntimeQuarantineEntry.rejectionDetail must be non-blank."
        else -> null
      }

    private fun regenerationViolation(
      regenerationAttempt: Int,
      quarantinedAtIteration: Int,
    ): String? =
      when {
        regenerationAttempt < 1 -> "FeatureTaskRuntimeQuarantineEntry.regenerationAttempt must be >= 1."
        quarantinedAtIteration < 1 -> "FeatureTaskRuntimeQuarantineEntry.quarantinedAtIteration must be >= 1."
        else -> null
      }

    private fun diagnosticViolation(
      diagnosticIdentity: String?,
      rejectedRecordByteSize: Long,
      rejectedRecordSha256: String,
      diagnosticDegraded: Boolean,
    ): String? =
      when {
        !(diagnosticDegraded xor (diagnosticIdentity != null)) ->
          "FeatureTaskRuntimeQuarantineEntry must carry diagnosticIdentity xor diagnosticDegraded=true."
        diagnosticIdentity != null && diagnosticIdentity.isBlank() ->
          "FeatureTaskRuntimeQuarantineEntry.diagnosticIdentity must be non-blank when present."
        rejectedRecordByteSize < 0 -> "FeatureTaskRuntimeQuarantineEntry.rejectedRecordByteSize must be >= 0."
        !Regex("[0-9a-f]{64}").matches(rejectedRecordSha256) ->
          "FeatureTaskRuntimeQuarantineEntry.rejectedRecordSha256 must be a lowercase SHA-256 digest."
        else -> null
      }

    private val ALLOWED_FIELDS: Set<String> =
      setOf(
        "producing_phase_id",
        "consuming_phase_id",
        "producing_iteration",
        "rejection_class",
        "rejection_detail",
        "regeneration_attempt",
        "quarantined_at_iteration",
        "diagnostic_identity",
        "diagnostic_degraded",
        "rejected_record_byte_size",
        "rejected_record_sha256",
      )

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeQuarantineEntry {
      val unexpected = raw.keys - ALLOWED_FIELDS
      if (unexpected.isNotEmpty()) {
        quarantineSchemaError(
          "Feature-task-runtime quarantine entry carries unsupported fields ${unexpected.sorted()}; " +
            "the store is left untouched rather than rewritten without that evidence.",
        )
      }
      val reader = durableArtifactMapReader(raw)
      val producingPhaseId = reader.requiredString("producing_phase_id")
      val consumingPhaseId = reader.requiredString("consuming_phase_id")
      val producingIteration = reader.requiredInt("producing_iteration")
      val rejectionClass = reader.requiredString("rejection_class")
      val rejectionDetail = reader.requiredString("rejection_detail")
      val regenerationAttempt = reader.requiredInt("regeneration_attempt")
      val quarantinedAtIteration = reader.requiredInt("quarantined_at_iteration")
      val diagnosticIdentity = reader.optionalString("diagnostic_identity")
      val rejectedRecordByteSize = reader.requiredInt("rejected_record_byte_size").toLong()
      val rejectedRecordSha256 = reader.requiredString("rejected_record_sha256")
      val diagnosticDegraded =
        when (reader.optionalBoolean("diagnostic_degraded")) {
          null -> false
          true -> true
          false ->
            quarantineSchemaError(
              "Feature-task-runtime quarantine entry 'diagnostic_degraded' must be true when present.",
            )
        }
      val reason =
        productionViolation(
          producingPhaseId,
          consumingPhaseId,
          producingIteration,
          rejectionClass,
          rejectionDetail,
        ) ?: regenerationViolation(regenerationAttempt, quarantinedAtIteration)
          ?: diagnosticViolation(diagnosticIdentity, rejectedRecordByteSize, rejectedRecordSha256, diagnosticDegraded)
      if (reason != null) quarantineSchemaError("Feature-task-runtime quarantine entry is malformed: $reason")
      return FeatureTaskRuntimeQuarantineEntry(
        producingPhaseId, consumingPhaseId, producingIteration, rejectionClass, rejectionDetail,
        regenerationAttempt, quarantinedAtIteration, diagnosticIdentity, rejectedRecordByteSize,
        rejectedRecordSha256, diagnosticDegraded,
      )
    }
  }
}

internal fun featureTaskRuntimeQuarantineRecordToWire(
  entries: List<FeatureTaskRuntimeQuarantineEntry>,
): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_QUARANTINE_ARTIFACT_CONTRACT_VERSION,
    "entries" to entries.map { it.toArtifactMap() },
  )

private fun quarantineSchemaError(detail: String): Nothing = throw invalidWorkflowStateSchemaError(detail)

internal fun featureTaskRuntimeQuarantineEntriesFromWire(raw: Any?): List<FeatureTaskRuntimeQuarantineEntry> {
  val map =
    JsonCodec.anyToStringAnyMap(raw)
      ?: quarantineSchemaError("Feature-task-runtime quarantine record must be an object.")
  val unexpected = map.keys - QUARANTINE_ENVELOPE_FIELDS
  if (unexpected.isNotEmpty()) {
    quarantineSchemaError(
      "Feature-task-runtime quarantine record carries unsupported fields ${unexpected.sorted()}; " +
        "the store is left untouched rather than rewritten without that evidence.",
    )
  }
  val version = map[SharedPayloadKeys.CONTRACT_VERSION] as? String
  if (version != FEATURE_TASK_RUNTIME_QUARANTINE_ARTIFACT_CONTRACT_VERSION) {
    quarantineSchemaError(
      "Feature-task-runtime quarantine record uses unsupported contract version " +
        "'${version.orEmpty()}'; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
    )
  }
  val entries =
    map["entries"] as? List<*>
      ?: quarantineSchemaError("Feature-task-runtime quarantine record must carry an 'entries' array.")
  return entries.map { entry ->
    FeatureTaskRuntimeQuarantineEntry.fromArtifactMap(
      JsonCodec.anyToStringAnyMap(entry)
        ?: quarantineSchemaError("Feature-task-runtime quarantine entry must be an object."),
    )
  }
}
