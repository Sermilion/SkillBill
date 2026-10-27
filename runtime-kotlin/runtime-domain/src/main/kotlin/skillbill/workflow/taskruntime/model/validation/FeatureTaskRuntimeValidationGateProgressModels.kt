package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationGateFindingPayloadKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull
import skillbill.workflow.model.persistence.artifact.asExactLongOrNull
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

enum class FeatureTaskRuntimeValidationGateRepairWindowPhase(val wireValue: String) {
  NONE("none"),
  FINDINGS_OPEN("findings_open"),
  ;

  companion object {
    fun fromWire(value: String?): FeatureTaskRuntimeValidationGateRepairWindowPhase =
      when (value) {
        null, NONE.wireValue -> NONE
        FINDINGS_OPEN.wireValue -> FINDINGS_OPEN
        else -> throw invalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.repair_window_phase must be 'none' or 'findings_open'.",
        )
      }
  }
}

enum class FeatureTaskRuntimeValidationGateTriageDisposition(val wireValue: String) {
  UNKNOWN("unknown"),
  NOT_REQUIRED("not_required"),
  NOT_STARTED("not_started"),
  ACCEPTED_EMPTY("accepted_empty"),
  CAPTURED("captured"),
  ;

  companion object {
    fun fromWire(value: String?): FeatureTaskRuntimeValidationGateTriageDisposition =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidWorkflowStateSchemaError("Validation gate triage disposition is unsupported.")
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
  val legacyUnverified: Boolean = false,
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
    val reason =
      if (legacyUnverified) {
        legacyViolation(
          durationMs,
          executedWorkUnits,
          isLegacyShape(command, exitCode, repositoryCheckpoint),
          executedChecks,
        )
      } else {
        executionViolation(durationMs, executedWorkUnits, command, exitCode, repositoryCheckpoint)
          ?: checksViolation(outcome, exitCode, executedChecksRecorded, executedChecks)
      }
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    internal fun isLegacyShape(
      command: String?,
      exitCode: Int?,
      repositoryCheckpoint: String?,
    ): Boolean = command == null && exitCode == null && repositoryCheckpoint == null

    internal fun legacyViolation(
      durationMs: Long,
      executedWorkUnits: Int,
      legacyShape: Boolean,
      executedChecks: List<String>,
    ): String? =
      when {
        durationMs < 0 -> "Validation gate duration_ms must be >= 0, was $durationMs."
        executedWorkUnits < 0 -> "Validation gate executed_work_units must be >= 0, was $executedWorkUnits."
        !legacyShape ->
          "A legacy unverified validation gate run must not carry command, exit code, or checkpoint."
        executedChecks.any(String::isBlank) -> "Validation gate executed check identities must be non-blank."
        else -> null
      }

    internal fun executionViolation(
      durationMs: Long,
      executedWorkUnits: Int,
      command: String?,
      exitCode: Int?,
      repositoryCheckpoint: String?,
    ): String? =
      when {
        durationMs < 0 -> "Validation gate duration_ms must be >= 0, was $durationMs."
        executedWorkUnits < 0 -> "Validation gate executed_work_units must be >= 0, was $executedWorkUnits."
        (command == null) != (exitCode == null) -> "Validation gate command and exit_code must be present together."
        command != null && command.isBlank() -> "Validation gate command must be non-blank when present."
        repositoryCheckpoint != null && repositoryCheckpoint.isBlank() ->
          "Validation gate repository_checkpoint must be non-blank when present."
        command.isNullOrBlank() || exitCode == null || repositoryCheckpoint.isNullOrBlank() ->
          "Validation gate run must retain command, exit code, and repository checkpoint."
        else -> null
      }

    internal fun checksViolation(
      outcome: ValidationGateRunOutcome,
      exitCode: Int?,
      executedChecksRecorded: Boolean,
      executedChecks: List<String>,
    ): String? =
      when {
        !executedChecksRecorded ->
          "Validation gate run must explicitly record executed_checks, including an empty list."
        outcome == ValidationGateRunOutcome.PASSED && exitCode != 0 ->
          "A passed validation gate run must have a zero command exit code."
        executedChecks.any(String::isBlank) -> "Validation gate executed check identities must be non-blank."
        else -> null
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
  val triageDisposition: FeatureTaskRuntimeValidationGateTriageDisposition =
    FeatureTaskRuntimeValidationGateTriageDisposition.UNKNOWN,
  val lastAgentUnfixedCriteria: List<String> = emptyList(),
  val pendingExternalOperation: String? = null,
  val pendingExternalCheckpoint: String? = null,
  val acceptedRepairOutputs: List<FeatureTaskRuntimeValidationGateRepairOutput> = emptyList(),
  val terminalOutcome: String? = null,
  val terminalReason: String? = null,
  val terminalFailureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val terminalFailureCode: String? = null,
) {
  init {
    val reason =
      violation(gateRunCount, gateRuns, repairsUsed)
        ?: if ((pendingExternalOperation == null) != (pendingExternalCheckpoint == null) ||
          pendingExternalOperation?.isBlank() == true || pendingExternalCheckpoint?.isBlank() == true
        ) {
          "Pending validation gate operation identity and checkpoint must be present together."
        } else {
          if (acceptedRepairOutputs.map { it.repairTurn }.distinct().size != acceptedRepairOutputs.size ||
            acceptedRepairOutputs.any { it.repairTurn > repairsUsed }
          ) {
            "Accepted validation gate repairs must have unique turns within the consumed budget."
          } else {
            terminalViolation()
              ?: triageViolation()
          }
        }
    require(reason == null) { reason.orEmpty() }
  }

  private fun terminalViolation(): String? {
    val invalidIdentity =
      (terminalOutcome == null) != (terminalReason == null) ||
        terminalOutcome !in setOf(null, "paused", "blocked") || terminalReason?.isBlank() == true
    val invalidFailure =
      terminalOutcome != "blocked" &&
        (terminalFailureDisposition != null || terminalFailureCode != null)
    val operationPending = terminalOutcome != null && pendingExternalOperation != null
    return if (invalidIdentity || invalidFailure || operationPending) {
      "Terminal validation gate outcome is incomplete or inconsistent."
    } else {
      null
    }
  }

  private fun triageViolation(): String? {
    val invalidPlan =
      when (triageDisposition) {
        FeatureTaskRuntimeValidationGateTriageDisposition.CAPTURED -> capturedTriagePlan.isNullOrBlank()
        else -> capturedTriagePlan != null
      }
    return if (invalidPlan) "Validation gate triage disposition conflicts with its captured plan." else null
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_CONTRACT_VERSION,
      ValidationEvidencePayloadKeys.GATE_RUN_COUNT to gateRunCount,
      ValidationEvidencePayloadKeys.GATE_RUNS to gateRuns.map { it.toArtifactMap() },
      ValidationEvidencePayloadKeys.REMAINING_FINDINGS to remainingFindings,
      ValidationEvidencePayloadKeys.COMPLETE_FINDINGS to completeFindings,
      ValidationEvidencePayloadKeys.REPAIR_WINDOW_PHASE to repairWindowPhase.wireValue,
      ValidationEvidencePayloadKeys.REPAIRS_USED to repairsUsed,
      ValidationEvidencePayloadKeys.CAPTURED_TRIAGE_PLAN to capturedTriagePlan,
      ValidationEvidencePayloadKeys.TRIAGE_DISPOSITION to triageDisposition.wireValue,
      ValidationEvidencePayloadKeys.LAST_AGENT_UNFIXED_CRITERIA to lastAgentUnfixedCriteria,
      ValidationEvidencePayloadKeys.PENDING_EXTERNAL_OPERATION to pendingExternalOperation,
      ValidationEvidencePayloadKeys.PENDING_EXTERNAL_CHECKPOINT to pendingExternalCheckpoint,
      ValidationEvidencePayloadKeys.ACCEPTED_REPAIR_OUTPUTS to
        acceptedRepairOutputs.map {
          mapOf(
            ValidationEvidencePayloadKeys.REPAIR_TURN to it.repairTurn,
            ValidationEvidencePayloadKeys.REPAIR_OUTPUT to it.output,
          )
        },
      ValidationEvidencePayloadKeys.TERMINAL_OUTCOME to terminalOutcome,
      ValidationEvidencePayloadKeys.TERMINAL_REASON to terminalReason,
      ValidationEvidencePayloadKeys.TERMINAL_FAILURE_DISPOSITION to terminalFailureDisposition?.wireValue,
      ValidationEvidencePayloadKeys.TERMINAL_FAILURE_CODE to terminalFailureCode,
    )

  companion object {
    internal fun violation(
      gateRunCount: Int,
      gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>,
      repairsUsed: Int,
    ): String? =
      when {
        gateRunCount < 0 ->
          "FeatureTaskRuntimeValidationGateProgress.gateRunCount must be >= 0, was $gateRunCount."
        gateRuns.size != gateRunCount ->
          "FeatureTaskRuntimeValidationGateProgress.gateRuns size ${gateRuns.size} " +
            "must equal gateRunCount $gateRunCount."
        repairsUsed < 0 ->
          "FeatureTaskRuntimeValidationGateProgress.repairsUsed must be >= 0, was $repairsUsed."
        else -> null
      }

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeValidationGateProgress {
      val contractVersion = raw[SharedPayloadKeys.CONTRACT_VERSION]
      if (
        contractVersion != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION &&
        contractVersion != FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_CONTRACT_VERSION
      ) {
        throw invalidWorkflowStateSchemaError("Unsupported validation gate progress contract_version.")
      }
      val gateRunCount = raw.asStarMap().gateProgressInt(ValidationEvidencePayloadKeys.GATE_RUN_COUNT)
      val gateRuns = decodeGateRuns(raw[ValidationEvidencePayloadKeys.GATE_RUNS])
      val remainingFindings =
        decodeFindings(
          raw[ValidationEvidencePayloadKeys.REMAINING_FINDINGS],
          ValidationEvidencePayloadKeys.REMAINING_FINDINGS,
        )
      val completeFindings =
        decodeFindings(
          raw[ValidationEvidencePayloadKeys.COMPLETE_FINDINGS],
          ValidationEvidencePayloadKeys.COMPLETE_FINDINGS,
        )
      val repairWindowPhase =
        FeatureTaskRuntimeValidationGateRepairWindowPhase.fromWire(
          raw[ValidationEvidencePayloadKeys.REPAIR_WINDOW_PHASE] as? String,
        )
      val repairsUsed = raw.asStarMap().gateProgressOptionalInt(ValidationEvidencePayloadKeys.REPAIRS_USED) ?: 0
      val capturedTriagePlan = raw[ValidationEvidencePayloadKeys.CAPTURED_TRIAGE_PLAN] as? String
      val triageDisposition = decodeTriageDisposition(raw, contractVersion, capturedTriagePlan)
      val lastAgentUnfixedCriteria =
        decodeStringList(
          raw[ValidationEvidencePayloadKeys.LAST_AGENT_UNFIXED_CRITERIA],
          ValidationEvidencePayloadKeys.LAST_AGENT_UNFIXED_CRITERIA,
        )
      val pendingExternalOperation = raw[ValidationEvidencePayloadKeys.PENDING_EXTERNAL_OPERATION] as? String
      val pendingExternalCheckpoint = raw[ValidationEvidencePayloadKeys.PENDING_EXTERNAL_CHECKPOINT] as? String
      val acceptedRepairOutputs =
        decodeAcceptedRepairOutputs(raw[ValidationEvidencePayloadKeys.ACCEPTED_REPAIR_OUTPUTS])
      val terminalOutcome = raw.optionalGateProgressString(ValidationEvidencePayloadKeys.TERMINAL_OUTCOME)
      val terminalReason = raw.optionalGateProgressString(ValidationEvidencePayloadKeys.TERMINAL_REASON)
      val terminalFailureDisposition = decodeTerminalFailureDisposition(raw)
      val terminalFailureCode = raw.optionalGateProgressString(ValidationEvidencePayloadKeys.TERMINAL_FAILURE_CODE)
      violation(gateRunCount, gateRuns, repairsUsed)?.let { reason ->
        throw invalidWorkflowStateSchemaError("FeatureTaskRuntimeValidationGateProgress is incoherent: $reason")
      }
      return FeatureTaskRuntimeValidationGateProgress(
        gateRunCount,
        gateRuns,
        remainingFindings,
        completeFindings,
        repairWindowPhase,
        repairsUsed,
        capturedTriagePlan,
        triageDisposition,
        lastAgentUnfixedCriteria,
        pendingExternalOperation,
        pendingExternalCheckpoint,
        acceptedRepairOutputs,
        terminalOutcome,
        terminalReason,
        terminalFailureDisposition,
        terminalFailureCode,
      )
    }

    private fun decodeTriageDisposition(
      raw: Map<String, Any?>,
      contractVersion: Any?,
      capturedTriagePlan: String?,
    ): FeatureTaskRuntimeValidationGateTriageDisposition {
      return if (raw.containsKey(ValidationEvidencePayloadKeys.TRIAGE_DISPOSITION)) {
        FeatureTaskRuntimeValidationGateTriageDisposition.fromWire(
          raw[ValidationEvidencePayloadKeys.TRIAGE_DISPOSITION] as? String,
        )
      } else if (contractVersion == FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_CONTRACT_VERSION) {
        throw invalidWorkflowStateSchemaError("Validation gate progress is missing triage_disposition.")
      } else if (capturedTriagePlan != null) {
        FeatureTaskRuntimeValidationGateTriageDisposition.CAPTURED
      } else {
        FeatureTaskRuntimeValidationGateTriageDisposition.UNKNOWN
      }
    }

    private fun decodeTerminalFailureDisposition(raw: Map<String, Any?>): FeatureTaskRuntimeFailureDisposition? {
      val wire = raw[ValidationEvidencePayloadKeys.TERMINAL_FAILURE_DISPOSITION]
      val disposition = (wire as? String)?.let(FeatureTaskRuntimeFailureDisposition::fromWireValue)
      if (wire != null && disposition == null) {
        throw invalidWorkflowStateSchemaError("Validation gate terminal failure disposition is unsupported.")
      }
      return disposition
    }

    private fun decodeAcceptedRepairOutputs(raw: Any?): List<FeatureTaskRuntimeValidationGateRepairOutput> =
      (raw as? List<*>)?.mapIndexed { index, value ->
        val entry =
          value as? Map<*, *>
            ?: throw invalidWorkflowStateSchemaError("Validation gate accepted repair output $index must be a mapping.")
        val turn =
          (entry[ValidationEvidencePayloadKeys.REPAIR_TURN] as? Number)?.toInt()
            ?: throw invalidWorkflowStateSchemaError("Validation gate accepted repair output $index has no turn.")
        val output =
          entry[ValidationEvidencePayloadKeys.REPAIR_OUTPUT] as? String
            ?: throw invalidWorkflowStateSchemaError("Validation gate accepted repair output $index has no output.")
        FeatureTaskRuntimeValidationGateRepairOutput(turn, output)
      }.orEmpty()

    private fun decodeGateRuns(raw: Any?): List<FeatureTaskRuntimeValidationGateRunRecord> {
      val runsRaw =
        raw as? List<*> ?: invalidGateRuns("FeatureTaskRuntimeValidationGateProgress is missing gate_runs.")
      return runsRaw.mapIndexed { index, entry ->
        val map =
          entry as? Map<*, *> ?: invalidGateRuns(
            "FeatureTaskRuntimeValidationGateProgress.gate_runs[$index] must be a mapping.",
          )
        val durationMs = map.gateProgressLong(ValidationEvidencePayloadKeys.DURATION_MS)
        val outcome =
          ValidationGateRunOutcome.fromWire(map.gateProgressString(ValidationEvidencePayloadKeys.OUTCOME))
            ?: invalidGateRuns(
              "FeatureTaskRuntimeValidationGateProgress.gate_runs[$index] is incoherent: Unknown validation gate " +
                "outcome.",
            )
        val cacheMode =
          ValidationGateCacheMode.fromWire(map.gateProgressString(ValidationEvidencePayloadKeys.CACHE_MODE))
            ?: invalidGateRuns(
              "FeatureTaskRuntimeValidationGateProgress.gate_runs[$index] is incoherent: Unknown validation gate " +
                "cache mode.",
            )
        val executedWorkUnits = map.gateProgressInt(ValidationEvidencePayloadKeys.EXECUTED_WORK_UNITS)
        val executedChecks = decodeExecutedChecks(map)
        val command = map.gateProgressOptionalString(ValidationEvidencePayloadKeys.COMMAND)
        val exitCode = map.gateProgressOptionalInt(ValidationEvidencePayloadKeys.EXIT_CODE)
        val repositoryCheckpoint = map.gateProgressOptionalString(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT)
        val executedChecksRecorded = map.containsKey(ValidationEvidencePayloadKeys.EXECUTED_CHECKS)
        val legacyUnverified =
          FeatureTaskRuntimeValidationGateRunRecord.isLegacyShape(command, exitCode, repositoryCheckpoint)
        val reason =
          if (legacyUnverified) {
            FeatureTaskRuntimeValidationGateRunRecord.legacyViolation(
              durationMs,
              executedWorkUnits,
              legacyShape = true,
              executedChecks,
            )
          } else {
            FeatureTaskRuntimeValidationGateRunRecord.executionViolation(
              durationMs,
              executedWorkUnits,
              command,
              exitCode,
              repositoryCheckpoint,
            ) ?: FeatureTaskRuntimeValidationGateRunRecord.checksViolation(
              outcome,
              exitCode,
              executedChecksRecorded,
              executedChecks,
            )
          }
        if (reason != null) {
          throw invalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.gate_runs[$index] is incoherent: $reason",
          )
        }
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs, outcome, cacheMode, executedWorkUnits, executedChecks, command,
          exitCode, repositoryCheckpoint, executedChecksRecorded, legacyUnverified,
        )
      }
    }

    private fun decodeExecutedChecks(map: Map<*, *>): List<String> {
      if (!map.containsKey(ValidationEvidencePayloadKeys.EXECUTED_CHECKS)) return emptyList()
      val raw = map[ValidationEvidencePayloadKeys.EXECUTED_CHECKS]
      val list =
        raw as? List<*>
          ?: throw invalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress gate run executed_checks must be a list.",
          )
      return list.mapIndexed { index, entry ->
        entry as? String ?: throw invalidWorkflowStateSchemaError(
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
          ?: throw invalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.$field must be a list.",
          )
      return list.mapIndexed { index, entry ->
        entry as? String ?: throw invalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.$field[$index] must be a string.",
        )
      }
    }

    private fun decodeFailureSubject(
      map: Map<*, *>,
      field: String,
    ): String {
      if (!map.containsKey(ValidationEvidencePayloadKeys.FAILURE_SUBJECT)) {
        return FeatureTaskRuntimeValidationFailureSubject.UNKNOWN.wireValue
      }
      val wire =
        map[ValidationEvidencePayloadKeys.FAILURE_SUBJECT] as? String
          ?: throw invalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.$field.failure_subject must be a string.",
          )
      return FeatureTaskRuntimeValidationFailureSubject.fromWire(wire)?.wireValue
        ?: throw invalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.$field.failure_subject is unsupported.",
        )
    }

    private fun decodeFindings(
      raw: Any?,
      field: String,
    ): List<Map<String, String?>> {
      if (raw == null) return emptyList()
      val list =
        raw as? List<*>
          ?: throw invalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.$field must be a list.",
          )
      return list.mapIndexed { index, entry ->
        val map =
          entry as? Map<*, *>
            ?: throw invalidWorkflowStateSchemaError(
              "FeatureTaskRuntimeValidationGateProgress.$field[$index] must be a mapping.",
            )
        val failureSubject = decodeFailureSubject(map, "$field[$index]")
        linkedMapOf(
          ValidationGateFindingPayloadKeys.MODULE to (map[ValidationGateFindingPayloadKeys.MODULE] as? String),
          ValidationGateFindingPayloadKeys.RULE_OR_TEST_ID to
            (map[ValidationGateFindingPayloadKeys.RULE_OR_TEST_ID] as? String),
          ValidationGateFindingPayloadKeys.MESSAGE to (map[ValidationGateFindingPayloadKeys.MESSAGE] as? String),
          ValidationGateFindingPayloadKeys.LOCATION to (map[ValidationGateFindingPayloadKeys.LOCATION] as? String),
          ValidationEvidencePayloadKeys.FAILURE_SUBJECT to failureSubject,
        )
      }
    }
  }
}

data class FeatureTaskRuntimeValidationGateRepairOutput(
  val repairTurn: Int,
  val output: String,
) {
  init {
    require(repairTurn > 0)
  }
}

internal fun Map<String, Any?>.asStarMap(): Map<*, *> = this

internal fun Map<*, *>.gateProgressString(key: String): String =
  this[key] as? String ?: throw invalidWorkflowStateSchemaError("Missing required string field '$key'.")

internal fun Map<*, *>.gateProgressInt(key: String): Int =
  this[key].asExactIntOrNull()
    ?: throw invalidWorkflowStateSchemaError("Missing required int field '$key'.")

internal fun Map<*, *>.gateProgressLong(key: String): Long =
  this[key].asExactLongOrNull()
    ?: throw invalidWorkflowStateSchemaError("Missing required long field '$key'.")

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

private fun Map<String, Any?>.optionalGateProgressString(key: String): String? {
  if (!containsKey(key) || this[key] == null) return null
  return this[key] as? String
    ?: throw invalidWorkflowStateSchemaError("Validation gate progress field '$key' must be a string or null.")
}

private fun invalidGateRuns(reason: String): Nothing = throw invalidWorkflowStateSchemaError(reason)
