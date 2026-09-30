package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

private const val MAX_VALIDATION_RESULTS = 50

data class FeatureTaskRuntimeValidationCommandResult(
  val command: String,
  val exitCode: Int,
) {
  init {
    require(command.isNotBlank()) { "Validation command must be non-blank." }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ValidationEvidencePayloadKeys.COMMAND to command,
      ValidationEvidencePayloadKeys.EXIT_CODE to exitCode,
    )
}

data class FeatureTaskRuntimeValidationEvidence(
  val results: List<FeatureTaskRuntimeValidationCommandResult>,
) {
  init {
    require(results.isNotEmpty()) { "Validation evidence must contain at least one result." }
    require(results.size <= MAX_VALIDATION_RESULTS) {
      "Validation evidence cannot contain more than $MAX_VALIDATION_RESULTS results."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ValidationEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
      ValidationEvidencePayloadKeys.RESULTS to results.map(FeatureTaskRuntimeValidationCommandResult::toArtifactMap),
    )

  fun requireSuccessfulCommand(
    requiredCommand: String,
    sourceLabel: String,
  ): FeatureTaskRuntimeValidationCommandResult {
    val result =
      results.lastOrNull()?.takeIf { it.command == requiredCommand }
        ?: throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
          sourceLabel,
          "The terminal result must identify the required validation command.",
        )
    if (result.exitCode != 0) {
      throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
        sourceLabel,
        "Required terminal validation command exited with ${result.exitCode}.",
      )
    }
    return result
  }

  fun requireSuccessfulResult(sourceLabel: String): FeatureTaskRuntimeValidationCommandResult {
    val result =
      results.lastOrNull()
        ?: throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
          sourceLabel,
          "validation evidence has no command results.",
        )
    if (result.exitCode != 0) {
      throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
        sourceLabel,
        "The final validation command exited with ${result.exitCode}.",
      )
    }
    return result
  }

  companion object {
    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): FeatureTaskRuntimeValidationEvidence {
      val allowed =
        setOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION,
          ValidationEvidencePayloadKeys.RESULTS,
        )
      val unknown = raw.keys - allowed
      if (unknown.isNotEmpty()) invalid(sourceLabel, "Unknown validation evidence fields.")
      val version =
        raw[ValidationEvidencePayloadKeys.CONTRACT_VERSION] as? String
          ?: invalid(sourceLabel, "contract_version is missing.")
      if (version != FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION) {
        invalid(
          sourceLabel,
          "Unsupported validation evidence contract_version.",
        )
      }
      val rawResults =
        raw[ValidationEvidencePayloadKeys.RESULTS] as? List<*>
          ?: invalid(sourceLabel, "results must be a list.")
      val results =
        rawResults.mapIndexed { index, item ->
          val result = item as? Map<*, *> ?: invalid(sourceLabel, "results[$index] must be a mapping.")
          if (result.keys.any { it !is String }) {
            invalid(sourceLabel, "results[$index] has a non-string key.")
          }
          val command =
            result[ValidationEvidencePayloadKeys.COMMAND] as? String
              ?: invalid(sourceLabel, "results[$index].command must be a string.")
          val exitCode =
            result[ValidationEvidencePayloadKeys.EXIT_CODE].asExactIntOrNull()
              ?: invalid(sourceLabel, "results[$index].exit_code must be an integer.")
          if (command.isBlank()) invalid(sourceLabel, "results[$index].command must be non-blank.")
          FeatureTaskRuntimeValidationCommandResult(command, exitCode)
        }
      return try {
        FeatureTaskRuntimeValidationEvidence(results)
      } catch (error: IllegalArgumentException) {
        invalid(sourceLabel, error.message.orEmpty())
      }
    }

    private fun invalid(
      sourceLabel: String,
      reason: String,
    ): Nothing = throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(sourceLabel, reason)
  }
}
