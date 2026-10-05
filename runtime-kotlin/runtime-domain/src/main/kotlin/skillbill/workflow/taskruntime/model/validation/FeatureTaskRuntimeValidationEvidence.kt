package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeValidationEvidenceSchema
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
    val reason = violation(results)
    require(reason == null) { reason.orEmpty() }
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
        ?: throw invalidFeatureTaskRuntimeValidationEvidenceSchema(
          sourceLabel,
          "The terminal result must identify the required validation command.",
        )
    if (result.exitCode != 0) {
      throw invalidFeatureTaskRuntimeValidationEvidenceSchema(
        sourceLabel,
        "Required terminal validation command exited with ${result.exitCode}.",
      )
    }
    return result
  }

  fun requireSuccessfulResult(
    sourceLabel: String,
    onInvalid: (String) -> SkillBillRuntimeException = {
      invalidFeatureTaskRuntimeValidationEvidenceSchema(sourceLabel, it)
    },
  ): FeatureTaskRuntimeValidationCommandResult {
    val result =
      results.lastOrNull()
        ?: throw onInvalid(
          "validation evidence has no command results.",
        )
    if (result.exitCode != 0) {
      throw onInvalid(
        "The final validation command exited with ${result.exitCode}.",
      )
    }
    return result
  }

  companion object {
    internal fun violation(results: List<FeatureTaskRuntimeValidationCommandResult>): String? =
      when {
        results.isEmpty() -> "Validation evidence must contain at least one result."
        results.size > MAX_VALIDATION_RESULTS ->
          "Validation evidence cannot contain more than $MAX_VALIDATION_RESULTS results."
        else -> null
      }

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      sourceLabel: String,
      onInvalid: (String) -> SkillBillRuntimeException = {
        invalidFeatureTaskRuntimeValidationEvidenceSchema(sourceLabel, it)
      },
    ): FeatureTaskRuntimeValidationEvidence {
      val allowed =
        setOf(
          ValidationEvidencePayloadKeys.CONTRACT_VERSION,
          ValidationEvidencePayloadKeys.RESULTS,
        )
      val unknown = raw.keys - allowed
      if (unknown.isNotEmpty()) invalid(onInvalid, "Unknown validation evidence fields.")
      val version =
        raw[ValidationEvidencePayloadKeys.CONTRACT_VERSION] as? String
          ?: invalid(onInvalid, "contract_version is missing.")
      if (version != FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION) {
        invalid(
          onInvalid,
          "Unsupported validation evidence contract_version.",
        )
      }
      val rawResults =
        raw[ValidationEvidencePayloadKeys.RESULTS] as? List<*>
          ?: invalid(onInvalid, "results must be a list.")
      val results =
        rawResults.mapIndexed { index, item ->
          val result = item as? Map<*, *> ?: invalid(onInvalid, "results[$index] must be a mapping.")
          if (result.keys.any { it !is String }) {
            invalid(onInvalid, "results[$index] has a non-string key.")
          }
          val command =
            result[ValidationEvidencePayloadKeys.COMMAND] as? String
              ?: invalid(onInvalid, "results[$index].command must be a string.")
          val exitCode =
            result[ValidationEvidencePayloadKeys.EXIT_CODE].asExactIntOrNull()
              ?: invalid(onInvalid, "results[$index].exit_code must be an integer.")
          if (command.isBlank()) invalid(onInvalid, "results[$index].command must be non-blank.")
          FeatureTaskRuntimeValidationCommandResult(command, exitCode)
        }
      violation(results)?.let { reason -> invalid(onInvalid, reason) }
      return FeatureTaskRuntimeValidationEvidence(results)
    }

    private fun invalid(
      onInvalid: (String) -> SkillBillRuntimeException,
      reason: String,
    ): Nothing = throw onInvalid(reason)
  }
}
