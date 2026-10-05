package skillbill.workflow.taskruntime.model.phase

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhaseOutputRepairEvidencePayloadKeys
import skillbill.error.shellcontent.invalidFeatureTaskRuntimePhaseOutputSchema
import skillbill.review.context.model.hunk.SHA256_HEX
import skillbill.workflow.model.persistence.artifact.DurableArtifactMapReader
import skillbill.workflow.model.persistence.artifact.toStringKeyedArtifactMap

enum class FeatureTaskRuntimePhaseOutputFormat(val wireValue: String) {
  JSON("json"),
  YAML("yaml"),

  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseOutputFormat =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidFeatureTaskRuntimePhaseOutputSchema(
          sourceLabel = "<wire>",
          reason = "Unrecognized phase-output format wire value '$value'.",
        )
  }
}

enum class FeatureTaskRuntimePhaseOutputRepairOperation(val wireValue: String) {
  REMOVE_EXTRA_CLOSING_DELIMITER("remove_extra_closing_delimiter"),
  ADD_MISSING_CLOSING_DELIMITER("add_missing_closing_delimiter"),
  DEDUPLICATE_KEYS("deduplicate_keys"),
  RESTORE_EXPECTED_SHAPE("restore_expected_shape"),

  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseOutputRepairOperation =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidFeatureTaskRuntimePhaseOutputSchema(
          sourceLabel = "<wire>",
          reason = "Unrecognized phase-output repair-operation wire value '$value'.",
        )
  }
}

data class FeatureTaskRuntimePhaseOutputSourceLocation(
  val sourceLabel: String,
  val offset: Int,
  val line: Int,
  val column: Int,
) {
  init {
    val reason = violation(sourceLabel, offset, line, column)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    internal fun violation(
      sourceLabel: String,
      offset: Int,
      line: Int,
      column: Int,
    ): String? =
      when {
        sourceLabel.isBlank() -> "Phase-output sourceLabel must be non-blank."
        offset < 0 -> "Phase-output source offset must be non-negative."
        line < 1 -> "Phase-output source line must be >= 1."
        column < 1 -> "Phase-output source column must be >= 1."
        else -> null
      }
  }
}

data class FeatureTaskRuntimePhaseOutputRepairEvidence(
  val contractVersion: String = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION,
  val validatorVersion: String = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION,
  val format: FeatureTaskRuntimePhaseOutputFormat,
  val originalDigest: String,
  val repairedDigest: String,
  val operation: FeatureTaskRuntimePhaseOutputRepairOperation,
  val sourceLocation: FeatureTaskRuntimePhaseOutputSourceLocation,
) {
  init {
    val reason = violation(contractVersion, validatorVersion, originalDigest, repairedDigest)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    internal fun violation(
      contractVersion: String,
      validatorVersion: String,
      originalDigest: String,
      repairedDigest: String,
    ): String? =
      when {
        contractVersion != FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION ->
          "Phase-output repair evidence has unsupported contract version '$contractVersion'."
        validatorVersion != FEATURE_TASK_RUNTIME_PHASE_OUTPUT_VALIDATION_VERSION ->
          "Phase-output repair evidence has unsupported validator version '$validatorVersion'."
        !originalDigest.matches(SHA256_HEX) ->
          "Phase-output repair evidence originalDigest must be lowercase SHA-256."
        !repairedDigest.matches(SHA256_HEX) ->
          "Phase-output repair evidence repairedDigest must be lowercase SHA-256."
        originalDigest == repairedDigest -> "Phase-output repair evidence must describe a changed payload."
        else -> null
      }

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseOutputRepairEvidence =
      decode(raw, null)

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      onInvariantViolation: () -> Nothing,
    ): FeatureTaskRuntimePhaseOutputRepairEvidence = decode(raw, onInvariantViolation)

    private fun decode(
      raw: Map<String, Any?>,
      onInvariantViolation: (() -> Nothing)?,
    ): FeatureTaskRuntimePhaseOutputRepairEvidence {
      requireRepairEvidenceExactFields(raw)
      val location = requireRepairEvidenceLocation(raw)
      val reader = DurableArtifactMapReader(raw) { message -> phaseOutputRepairEvidenceSchemaError(message) }
      val locationReader =
        DurableArtifactMapReader(location) { message -> phaseOutputRepairEvidenceSchemaError(message) }
      val contractVersion = reader.requiredString(SharedPayloadKeys.CONTRACT_VERSION)
      val validatorVersion =
        reader.requiredString(FeatureTaskRuntimePhaseOutputRepairEvidencePayloadKeys.VALIDATOR_VERSION)
      val format = FeatureTaskRuntimePhaseOutputFormat.fromWire(reader.requiredString("format"))
      val originalDigest = reader.requiredString("original_digest")
      val repairedDigest = reader.requiredString("repaired_digest")
      val operation = FeatureTaskRuntimePhaseOutputRepairOperation.fromWire(reader.requiredString("operation"))
      val sourceLabel = locationReader.requiredString("source_label")
      val offset = locationReader.requiredInt("offset")
      val line = locationReader.requiredInt("line")
      val column = locationReader.requiredInt("column")
      val locationReason = FeatureTaskRuntimePhaseOutputSourceLocation.violation(sourceLabel, offset, line, column)
      if (locationReason != null && onInvariantViolation != null) onInvariantViolation()
      val sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation(sourceLabel, offset, line, column)
      val evidenceReason = violation(contractVersion, validatorVersion, originalDigest, repairedDigest)
      if (evidenceReason != null && onInvariantViolation != null) onInvariantViolation()
      return FeatureTaskRuntimePhaseOutputRepairEvidence(
        contractVersion,
        validatorVersion,
        format,
        originalDigest,
        repairedDigest,
        operation,
        sourceLocation,
      )
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to contractVersion,
      FeatureTaskRuntimePhaseOutputRepairEvidencePayloadKeys.VALIDATOR_VERSION to validatorVersion,
      "format" to format.wireValue,
      "original_digest" to originalDigest,
      "repaired_digest" to repairedDigest,
      "operation" to operation.wireValue,
      "source_location" to
        linkedMapOf(
          "source_label" to sourceLocation.sourceLabel,
          "offset" to sourceLocation.offset,
          "line" to sourceLocation.line,
          "column" to sourceLocation.column,
        ),
    )
}

private fun phaseOutputRepairEvidenceSchemaError(reason: String): Nothing =
  throw invalidFeatureTaskRuntimePhaseOutputSchema(
    sourceLabel = "repair_evidence",
    reason = reason,
  )

private fun requireRepairEvidenceExactFields(raw: Map<String, Any?>) {
  val expectedFields =
    setOf(
      SharedPayloadKeys.CONTRACT_VERSION,
      FeatureTaskRuntimePhaseOutputRepairEvidencePayloadKeys.VALIDATOR_VERSION,
      "format",
      "original_digest",
      "repaired_digest",
      "operation",
      "source_location",
    )
  if (raw.keys != expectedFields) {
    phaseOutputRepairEvidenceSchemaError(
      "Phase-output repair evidence contains unsupported or missing fields.",
    )
  }
}

private fun requireRepairEvidenceLocation(raw: Map<String, Any?>): Map<String, Any?> {
  val location =
    raw["source_location"]
      ?: phaseOutputRepairEvidenceSchemaError(
        "Phase-output repair evidence source_location must be an object.",
      )
  val locationMap =
    raw["source_location"] as? Map<*, *>
      ?: phaseOutputRepairEvidenceSchemaError("Phase-output repair evidence source_location must be an object.")
  val converted =
    locationMap.toStringKeyedArtifactMap { detail ->
      phaseOutputRepairEvidenceSchemaError("Phase-output repair evidence source_location $detail")
    }
  if (converted.keys != setOf("source_label", "offset", "line", "column")) {
    phaseOutputRepairEvidenceSchemaError(
      "Phase-output repair evidence source_location contains unsupported fields.",
    )
  }
  return converted
}
