package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull
import skillbill.workflow.model.persistence.artifact.asExactLongOrNull

enum class FeatureTaskRuntimeValidationGateRepairWindowPhase(val wireValue: String) {
  NONE("none"),
  FINDINGS_OPEN("findings_open"),
  ;

  companion object {
    fun fromWire(value: String?): FeatureTaskRuntimeValidationGateRepairWindowPhase =
      when (value) {
        null, NONE.wireValue -> NONE
        FINDINGS_OPEN.wireValue -> FINDINGS_OPEN
        else -> throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.repair_window_phase must be 'none' or 'findings_open'.",
        )
      }
  }
}

data class FeatureTaskRuntimeValidationGateRunRecord(
  val durationMs: Long,
  val outcome: ValidationGateRunOutcome,
  val cacheMode: ValidationGateCacheMode,
  val executedWorkUnits: Int,
  val executedChecks: List<String> = emptyList(),
  val command: String? = null,
  val exitCode: Int? = null,
  val repositoryCheckpoint: String? = null,
  val executedChecksRecorded: Boolean = true,
) {
  constructor(
    durationMs: Long,
    outcome: String,
    cacheMode: String,
    executedWorkUnits: Int,
    executedChecks: List<String> = emptyList(),
    command: String? = null,
    exitCode: Int? = null,
    repositoryCheckpoint: String? = null,
    executedChecksRecorded: Boolean = true,
  ) : this(
    durationMs = durationMs,
    outcome =
      requireNotNull(ValidationGateRunOutcome.fromWire(outcome)) {
        "Unknown validation gate outcome '$outcome'."
      },
    cacheMode =
      requireNotNull(ValidationGateCacheMode.fromWire(cacheMode)) {
        "Unknown validation gate cache mode '$cacheMode'."
      },
    executedWorkUnits = executedWorkUnits,
    executedChecks = executedChecks,
    command = command,
    exitCode = exitCode,
    repositoryCheckpoint = repositoryCheckpoint,
    executedChecksRecorded = executedChecksRecorded,
  )

  init {
    require(durationMs >= 0) {
      "Validation gate duration_ms must be >= 0, was $durationMs."
    }
    require(executedWorkUnits >= 0) {
      "Validation gate executed_work_units must be >= 0, was $executedWorkUnits."
    }
    require((command == null) == (exitCode == null)) {
      "Validation gate command and exit_code must be present together."
    }
    require(command == null || command.isNotBlank()) {
      "Validation gate command must be non-blank when present."
    }
    require(repositoryCheckpoint == null || repositoryCheckpoint.isNotBlank()) {
      "Validation gate repository_checkpoint must be non-blank when present."
    }
    require(!command.isNullOrBlank() && exitCode != null && !repositoryCheckpoint.isNullOrBlank()) {
      "Validation gate run must retain command, exit code, and repository checkpoint."
    }
    require(executedChecksRecorded) {
      "Validation gate run must explicitly record executed_checks, including an empty list."
    }
    require(outcome != ValidationGateRunOutcome.PASSED || exitCode == 0) {
      "A passed validation gate run must have a zero command exit code."
    }
    require(executedChecks.all { it.isNotBlank() }) {
      "Validation gate executed check identities must be non-blank."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      ValidationEvidencePayloadKeys.DURATION_MS to durationMs,
      ValidationEvidencePayloadKeys.OUTCOME to outcome.wireValue,
      ValidationEvidencePayloadKeys.CACHE_MODE to cacheMode.wireValue,
      ValidationEvidencePayloadKeys.EXECUTED_WORK_UNITS to executedWorkUnits,
    ).apply {
      if (executedChecksRecorded) {
        put(ValidationEvidencePayloadKeys.EXECUTED_CHECKS, executedChecks)
      }
      command?.let { put(ValidationEvidencePayloadKeys.COMMAND, it) }
      exitCode?.let { put(ValidationEvidencePayloadKeys.EXIT_CODE, it) }
      repositoryCheckpoint?.let { put(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT, it) }
    }
}

data class FeatureTaskRuntimeValidationGateProgress(
  val gateRunCount: Int,
  val gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>,
  val remainingFindings: List<Map<String, String?>> = emptyList(),
  val completeFindings: List<Map<String, String?>> = emptyList(),
  val repairWindowPhase: FeatureTaskRuntimeValidationGateRepairWindowPhase =
    FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE,
  val repairsUsed: Int = 0,
  val capturedTriagePlan: String? = null,
  val lastAgentUnfixedCriteria: List<String> = emptyList(),
) {
  init {
    require(gateRunCount >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.gateRunCount must be >= 0, was $gateRunCount."
    }
    require(gateRuns.size == gateRunCount) {
      "FeatureTaskRuntimeValidationGateProgress.gateRuns size ${gateRuns.size} " +
        "must equal gateRunCount $gateRunCount."
    }
    require(repairsUsed >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.repairsUsed must be >= 0, was $repairsUsed."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      ValidationEvidencePayloadKeys.GATE_RUN_COUNT to gateRunCount,
      ValidationEvidencePayloadKeys.GATE_RUNS to gateRuns.map { it.toArtifactMap() },
      "remaining_findings" to remainingFindings,
      "complete_findings" to completeFindings,
      "repair_window_phase" to repairWindowPhase.wireValue,
      "repairs_used" to repairsUsed,
      "captured_triage_plan" to capturedTriagePlan,
      ValidationEvidencePayloadKeys.LAST_AGENT_UNFIXED_CRITERIA to lastAgentUnfixedCriteria,
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeValidationGateProgress =
      try {
        if (raw[SharedPayloadKeys.CONTRACT_VERSION] != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION) {
          throw InvalidWorkflowStateSchemaError("Unsupported validation gate progress contract_version.")
        }
        FeatureTaskRuntimeValidationGateProgress(
          gateRunCount = raw.asStarMap().gateProgressInt(ValidationEvidencePayloadKeys.GATE_RUN_COUNT),
          gateRuns = decodeGateRuns(raw[ValidationEvidencePayloadKeys.GATE_RUNS]),
          remainingFindings = decodeFindings(raw["remaining_findings"], "remaining_findings"),
          completeFindings = decodeFindings(raw["complete_findings"], "complete_findings"),
          repairWindowPhase =
            FeatureTaskRuntimeValidationGateRepairWindowPhase.fromWire(
              raw["repair_window_phase"] as? String,
            ),
          repairsUsed = raw.asStarMap().gateProgressOptionalInt("repairs_used") ?: 0,
          capturedTriagePlan = raw["captured_triage_plan"] as? String,
          lastAgentUnfixedCriteria =
            decodeStringList(
              raw[ValidationEvidencePayloadKeys.LAST_AGENT_UNFIXED_CRITERIA],
              ValidationEvidencePayloadKeys.LAST_AGENT_UNFIXED_CRITERIA,
            ),
        )
      } catch (error: IllegalArgumentException) {
        throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress is incoherent: ${error.message.orEmpty()}",
          error,
        )
      }

    private fun decodeGateRuns(raw: Any?): List<FeatureTaskRuntimeValidationGateRunRecord> {
      val runsRaw =
        raw as? List<*> ?: invalidGateRuns("FeatureTaskRuntimeValidationGateProgress is missing gate_runs.")
      return runsRaw.mapIndexed { index, entry ->
        val map =
          entry as? Map<*, *> ?: invalidGateRuns(
            "FeatureTaskRuntimeValidationGateProgress.gate_runs[$index] must be a mapping.",
          )
        try {
          FeatureTaskRuntimeValidationGateRunRecord(
            durationMs = map.gateProgressLong(ValidationEvidencePayloadKeys.DURATION_MS),
            outcome =
              requireNotNull(
                ValidationGateRunOutcome.fromWire(map.gateProgressString(ValidationEvidencePayloadKeys.OUTCOME)),
              ) {
                "Unknown validation gate outcome."
              },
            cacheMode =
              requireNotNull(
                ValidationGateCacheMode.fromWire(map.gateProgressString(ValidationEvidencePayloadKeys.CACHE_MODE)),
              ) {
                "Unknown validation gate cache mode."
              },
            executedWorkUnits = map.gateProgressInt(ValidationEvidencePayloadKeys.EXECUTED_WORK_UNITS),
            executedChecks = decodeExecutedChecks(map),
            command = map.gateProgressOptionalString(ValidationEvidencePayloadKeys.COMMAND),
            exitCode = map.gateProgressOptionalInt(ValidationEvidencePayloadKeys.EXIT_CODE),
            repositoryCheckpoint =
              map.gateProgressOptionalString(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT),
            executedChecksRecorded = map.containsKey(ValidationEvidencePayloadKeys.EXECUTED_CHECKS),
          )
        } catch (error: IllegalArgumentException) {
          throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.gate_runs[$index] is incoherent: ${error.message.orEmpty()}",
            error,
          )
        }
      }
    }

    private fun decodeExecutedChecks(map: Map<*, *>): List<String> {
      if (!map.containsKey(ValidationEvidencePayloadKeys.EXECUTED_CHECKS)) return emptyList()
      val raw = map[ValidationEvidencePayloadKeys.EXECUTED_CHECKS]
      val list =
        raw as? List<*>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress gate run executed_checks must be a list.",
          )
      return list.mapIndexed { index, entry ->
        entry as? String ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress gate run executed_checks[$index] must be a string.",
        )
      }
    }

    private fun decodeStringList(
      raw: Any?,
      field: String,
    ): List<String> {
      if (raw == null) return emptyList()
      val list =
        raw as? List<*>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.$field must be a list.",
          )
      return list.mapIndexed { index, entry ->
        entry as? String ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.$field[$index] must be a string.",
        )
      }
    }

    private fun decodeFindings(
      raw: Any?,
      field: String,
    ): List<Map<String, String?>> {
      if (raw == null) return emptyList()
      val list =
        raw as? List<*>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.$field must be a list.",
          )
      return list.mapIndexed { index, entry ->
        val map =
          entry as? Map<*, *>
            ?: throw InvalidWorkflowStateSchemaError(
              "FeatureTaskRuntimeValidationGateProgress.$field[$index] must be a mapping.",
            )
        linkedMapOf(
          "module" to (map["module"] as? String),
          "rule_or_test_id" to (map["rule_or_test_id"] as? String),
          "message" to (map["message"] as? String),
          "location" to (map["location"] as? String),
        )
      }
    }
  }
}

internal fun Map<String, Any?>.asStarMap(): Map<*, *> = this

internal fun Map<*, *>.gateProgressString(key: String): String =
  this[key] as? String ?: throw InvalidWorkflowStateSchemaError("Missing required string field '$key'.")

internal fun Map<*, *>.gateProgressInt(key: String): Int =
  this[key].asExactIntOrNull()
    ?: throw InvalidWorkflowStateSchemaError("Missing required int field '$key'.")

internal fun Map<*, *>.gateProgressLong(key: String): Long =
  this[key].asExactLongOrNull()
    ?: throw InvalidWorkflowStateSchemaError("Missing required long field '$key'.")

internal fun Map<*, *>.gateProgressOptionalInt(key: String): Int? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return gateProgressInt(key)
}

internal fun Map<*, *>.gateProgressOptionalString(key: String): String? {
  if (!containsKey(key) || this[key] == null) return null
  return gateProgressString(key)
}

private fun invalidGateRuns(reason: String): Nothing = throw InvalidWorkflowStateSchemaError(reason)
