package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

enum class FeatureTaskRuntimeReadinessCheckStatus(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  SKIPPED("skipped"),
  MISSING("missing"),
  UNPERSISTED("unpersisted"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeReadinessCheckStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

data class FeatureTaskRuntimeReadinessCheckResult(
  val checkId: String,
  val command: String,
  val exitCode: Int,
  val status: FeatureTaskRuntimeReadinessCheckStatus,
) {
  init {
    require(checkId.isNotBlank()) { "Readiness check_id must be non-blank." }
    require(command.isNotBlank()) { "Readiness command must be non-blank." }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ReadinessEvidencePayloadKeys.CHECK_ID to checkId,
      ReadinessEvidencePayloadKeys.COMMAND to command,
      ReadinessEvidencePayloadKeys.EXIT_CODE to exitCode,
      ReadinessEvidencePayloadKeys.STATUS to status.wireValue,
    )
}

data class FeatureTaskRuntimeReadinessEvidence(
  val sourceTreeSha: String,
  val baseRefSha: String,
  val headSha: String,
  val selectedChecks: List<String>,
  val checkResults: List<FeatureTaskRuntimeReadinessCheckResult>,
) {
  init {
    require(sourceTreeSha.isNotBlank()) { "Readiness source_tree_sha must be non-blank." }
    require(baseRefSha.isNotBlank()) { "Readiness base_ref_sha must be non-blank." }
    require(headSha.isNotBlank()) { "Readiness head_sha must be non-blank." }
    require(selectedChecks.size <= MAX_READINESS_CHECK_RESULTS) {
      "Readiness evidence cannot select more than $MAX_READINESS_CHECK_RESULTS checks."
    }
    require(selectedChecks.all { it.isNotBlank() }) { "Readiness selected_checks must be non-blank." }
    require(selectedChecks.distinct().size == selectedChecks.size) {
      "Readiness selected_checks must be unique."
    }
    require(checkResults.size <= MAX_READINESS_CHECK_RESULTS) {
      "Readiness evidence cannot contain more than $MAX_READINESS_CHECK_RESULTS check results."
    }
    require(
      checkResults.map(FeatureTaskRuntimeReadinessCheckResult::checkId).distinct().size == checkResults.size,
    ) {
      "Readiness check_results must contain at most one result per check."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      ReadinessEvidencePayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION,
      ReadinessEvidencePayloadKeys.SOURCE_TREE_SHA to sourceTreeSha,
      ReadinessEvidencePayloadKeys.BASE_REF_SHA to baseRefSha,
      ReadinessEvidencePayloadKeys.HEAD_SHA to headSha,
      ReadinessEvidencePayloadKeys.SELECTED_CHECKS to selectedChecks,
      ReadinessEvidencePayloadKeys.CHECK_RESULTS to
        checkResults.map(
          FeatureTaskRuntimeReadinessCheckResult::toArtifactMap,
        ),
    )

  fun requireReady(
    sourceLabel: String,
    expectedSourceTreeSha: String,
    expectedBaseRefSha: String,
    expectedHeadSha: String,
  ) {
    if (sourceTreeSha != expectedSourceTreeSha) {
      invalid(
        sourceLabel,
        "source_tree_sha mismatch: captured '$sourceTreeSha' vs current '$expectedSourceTreeSha'; " +
          "base captured '$baseRefSha' vs current '$expectedBaseRefSha'; " +
          "head captured '$headSha' vs current '$expectedHeadSha'.",
      )
    }
    if (baseRefSha != expectedBaseRefSha) {
      invalid(
        sourceLabel,
        "base_ref_sha mismatch: captured '$baseRefSha' vs current '$expectedBaseRefSha'; " +
          "head captured '$headSha' vs current '$expectedHeadSha'.",
      )
    }
    if (headSha != expectedHeadSha) {
      invalid(
        sourceLabel,
        "head_sha mismatch: captured '$headSha' vs current '$expectedHeadSha'; " +
          "base captured '$baseRefSha' vs current '$expectedBaseRefSha'.",
      )
    }
    val resultsById = checkResults.associateBy(FeatureTaskRuntimeReadinessCheckResult::checkId)
    selectedChecks.forEach { checkId ->
      val result =
        resultsById[checkId]
          ?: invalid(sourceLabel, "selected check '$checkId' has no persisted result.")
      when (result.status) {
        FeatureTaskRuntimeReadinessCheckStatus.PASSED ->
          if (result.exitCode != 0) {
            invalid(sourceLabel, "selected check '$checkId' exited with ${result.exitCode}.")
          }
        FeatureTaskRuntimeReadinessCheckStatus.FAILED,
        FeatureTaskRuntimeReadinessCheckStatus.SKIPPED,
        FeatureTaskRuntimeReadinessCheckStatus.MISSING,
        FeatureTaskRuntimeReadinessCheckStatus.UNPERSISTED,
        -> invalid(sourceLabel, "selected check '$checkId' is not ready (status=${result.status.wireValue}).")
      }
    }
  }

  companion object {
    private const val MAX_READINESS_CHECK_RESULTS = 20

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): FeatureTaskRuntimeReadinessEvidence {
      val allowed =
        setOf(
          ReadinessEvidencePayloadKeys.CONTRACT_VERSION,
          ReadinessEvidencePayloadKeys.SOURCE_TREE_SHA,
          ReadinessEvidencePayloadKeys.BASE_REF_SHA,
          ReadinessEvidencePayloadKeys.HEAD_SHA,
          ReadinessEvidencePayloadKeys.SELECTED_CHECKS,
          ReadinessEvidencePayloadKeys.CHECK_RESULTS,
        )
      val unknown = raw.keys - allowed
      if (unknown.isNotEmpty()) invalid(sourceLabel, "unknown keys ${unknown.sorted()}.")
      val version =
        requiredString(
          raw,
          ReadinessEvidencePayloadKeys.CONTRACT_VERSION,
          sourceLabel,
        )
      if (version != FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION) {
        invalid(
          sourceLabel,
          "unsupported contract_version '$version'; expected " +
            "'$FEATURE_TASK_RUNTIME_READINESS_EVIDENCE_CONTRACT_VERSION'.",
        )
      }
      val sourceTreeSha =
        requiredString(
          raw,
          ReadinessEvidencePayloadKeys.SOURCE_TREE_SHA,
          sourceLabel,
        )
      val baseRefSha =
        requiredString(
          raw,
          ReadinessEvidencePayloadKeys.BASE_REF_SHA,
          sourceLabel,
        )
      val headSha =
        requiredString(
          raw,
          ReadinessEvidencePayloadKeys.HEAD_SHA,
          sourceLabel,
        )
      val selectedChecks = selectedChecks(raw, sourceLabel)
      val checkResults = checkResults(raw, sourceLabel)
      return try {
        FeatureTaskRuntimeReadinessEvidence(sourceTreeSha, baseRefSha, headSha, selectedChecks, checkResults)
      } catch (error: IllegalArgumentException) {
        invalid(sourceLabel, error.message.orEmpty())
      }
    }

    private fun requiredString(
      raw: Map<String, Any?>,
      key: String,
      sourceLabel: String,
    ): String = raw[key] as? String ?: invalid(sourceLabel, "$key must be a string.")

    private fun selectedChecks(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<String> {
      val selected =
        raw[ReadinessEvidencePayloadKeys.SELECTED_CHECKS] as? List<*>
          ?: invalid(sourceLabel, "selected_checks must be a list.")
      return selected.mapIndexed { index, item ->
        (item as? String)?.takeIf { it.isNotBlank() }
          ?: invalid(sourceLabel, "selected_checks[$index] must be a non-blank string.")
      }
    }

    private fun checkResults(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<FeatureTaskRuntimeReadinessCheckResult> {
      val rawResults =
        raw[ReadinessEvidencePayloadKeys.CHECK_RESULTS] as? List<*>
          ?: invalid(sourceLabel, "check_results must be a list.")
      return rawResults.mapIndexed { index, item -> checkResult(item, index, sourceLabel) }
    }

    private fun checkResult(
      item: Any?,
      index: Int,
      sourceLabel: String,
    ): FeatureTaskRuntimeReadinessCheckResult {
      val result = item as? Map<*, *> ?: invalid(sourceLabel, "check_results[$index] must be a mapping.")
      if (result.keys.any { it !is String }) {
        invalid(sourceLabel, "check_results[$index] has a non-string key.")
      }
      val checkId =
        result[ReadinessEvidencePayloadKeys.CHECK_ID] as? String
          ?: invalid(sourceLabel, "check_results[$index].check_id must be a string.")
      val command =
        result[ReadinessEvidencePayloadKeys.COMMAND] as? String
          ?: invalid(sourceLabel, "check_results[$index].command must be a string.")
      val exitCode =
        result[ReadinessEvidencePayloadKeys.EXIT_CODE].asExactIntOrNull()
          ?: invalid(sourceLabel, "check_results[$index].exit_code must be an integer.")
      val statusWire =
        result[ReadinessEvidencePayloadKeys.STATUS] as? String
          ?: invalid(sourceLabel, "check_results[$index].status must be a string.")
      val status =
        FeatureTaskRuntimeReadinessCheckStatus.fromWire(statusWire)
          ?: invalid(sourceLabel, "check_results[$index].status '$statusWire' is unsupported.")
      return FeatureTaskRuntimeReadinessCheckResult(checkId, command, exitCode, status)
    }

    private fun invalid(
      sourceLabel: String,
      reason: String,
    ): Nothing = throw InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError(sourceLabel, reason)
  }
}
