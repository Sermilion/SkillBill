package skillbill.workflow.taskruntime.model.validation

import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeValidationEvidenceSchema
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

data class FeatureTaskRuntimeValidationGateExecutionEvidence(
  val validationStatus: String,
  val checks: List<String>,
  val gateRunCount: Int,
  val gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>,
) {
  init {
    val reason = violation(validationStatus, checks, gateRunCount, gateRuns)
    require(reason == null) { reason.orEmpty() }
  }

  val zeroWork: Boolean
    get() =
      gateRuns.isNotEmpty() &&
        gateRuns.all { it.executedWorkUnits == 0 && it.executedChecks.isEmpty() }

  val evidenceRecorded: Boolean
    get() = gateRuns.isNotEmpty() && gateRuns.all { it.executedChecksRecorded }

  internal fun toArtifactMap(repositoryCheckpoint: String): Map<String, Any?> {
    require(repositoryCheckpoint.isNotBlank()) {
      "FeatureTaskRuntimeValidationGateExecutionEvidence.repositoryCheckpoint must be non-blank."
    }
    if (gateRuns.lastOrNull()?.repositoryCheckpoint != repositoryCheckpoint) {
      invalid("gate execution evidence", "Receipt checkpoint must match the terminal command.")
    }
    return linkedMapOf(
      ValidationEvidencePayloadKeys.VALIDATION_STATUS to validationStatus,
      ValidationEvidencePayloadKeys.CHECKS to checks,
      ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT to
        mapOf(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to repositoryCheckpoint),
      ValidationEvidencePayloadKeys.GATE_RUN_COUNT to gateRunCount,
      ValidationEvidencePayloadKeys.GATE_RUNS to gateRuns.map { it.toArtifactMap() },
    )
  }

  companion object {
    internal fun violation(
      validationStatus: String,
      checks: List<String>,
      gateRunCount: Int,
      gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>,
    ): String? =
      basicViolation(validationStatus, gateRunCount, gateRuns)
        ?: (if (validationStatus == "passed") passedViolation(gateRuns) else null)
        ?: when {
          checks != aggregateChecks(gateRuns) -> "Aggregate checks must match the recorded gate runs."
          checks.any(String::isBlank) ->
            "FeatureTaskRuntimeValidationGateExecutionEvidence.checks must be non-blank strings."
          else -> null
        }

    private fun basicViolation(
      validationStatus: String,
      gateRunCount: Int,
      gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>,
    ): String? = when {
      validationStatus.isBlank() ->
        "FeatureTaskRuntimeValidationGateExecutionEvidence.validationStatus must be non-blank."
      gateRunCount < 0 -> "FeatureTaskRuntimeValidationGateExecutionEvidence.gateRunCount must be >= 0."
      gateRuns.size != gateRunCount ->
        "FeatureTaskRuntimeValidationGateExecutionEvidence.gateRuns size ${gateRuns.size} " +
          "must equal gateRunCount $gateRunCount."
      else -> null
    }

    private fun passedViolation(gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>): String? = when {
      gateRuns.isEmpty() -> "Passed validation evidence must contain a gate run."
      gateRuns.last().outcome != ValidationGateRunOutcome.PASSED || gateRuns.last().exitCode != 0 ->
        "Passed validation evidence must end with a successful required command."
      gateRuns.any {
        it.command.isNullOrBlank() || it.exitCode == null || it.repositoryCheckpoint.isNullOrBlank()
      } -> "Validation gate runs must retain command, exit code, and repository checkpoint evidence."
      gateRuns.any { !it.executedChecksRecorded } ->
        "Validation gate runs must explicitly record executed_checks, including an empty list."
      gateRuns.any {
        it.outcome == ValidationGateRunOutcome.PASSED && it.exitCode != 0
      } -> "Passed validation gate outcomes must have zero command exit codes."
      else -> null
    }

    fun fromGateMeasurements(
      measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
    ): FeatureTaskRuntimeValidationGateExecutionEvidence {
      val checks = aggregateChecks(measurements)
      violation("passed", checks, measurements.size, measurements)?.let { reason ->
        invalid("gate execution evidence", reason)
      }
      return FeatureTaskRuntimeValidationGateExecutionEvidence("passed", checks, measurements.size, measurements)
    }

    fun aggregateChecks(measurements: List<FeatureTaskRuntimeValidationGateRunRecord>): List<String> =
      measurements.flatMap { it.executedChecks }.distinct().sorted()

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      sourceLabel: String,
      onInvalid: (String, SkillBillRuntimeException) -> SkillBillRuntimeException = { _, failure -> failure },
    ): FeatureTaskRuntimeValidationGateExecutionEvidence {
      val failureFor: (String) -> SkillBillRuntimeException = { reason ->
        onInvalid(reason, invalidFeatureTaskRuntimeValidationEvidenceSchema(sourceLabel, reason))
      }
      val validationStatus =
        raw[ValidationEvidencePayloadKeys.VALIDATION_STATUS] as? String
          ?: invalid(failureFor, "validation_status is missing.")
      val checks = decodeChecks(raw, failureFor)
      val receiptCheckpoint =
        decodeRepositoryCheckpoint(raw[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT], failureFor)
      val gateRunCount =
        raw[ValidationEvidencePayloadKeys.GATE_RUN_COUNT].asExactIntOrNull()
          ?: invalid(failureFor, "gate_run_count must be an integer.")
      val gateRuns = decodeGateRuns(raw[ValidationEvidencePayloadKeys.GATE_RUNS], sourceLabel, failureFor, onInvalid)
      val aggregateChecks = aggregateChecks(gateRuns)
      if (gateRuns.lastOrNull()?.repositoryCheckpoint != receiptCheckpoint) {
        invalid(failureFor, "repository_checkpoint must match the terminal gate run checkpoint.")
      }
      if (checks != aggregateChecks) {
        invalid(
          failureFor,
          "checks must equal the distinct sorted executed check identities from gate_runs.",
        )
      }
      violation(validationStatus, checks, gateRunCount, gateRuns)?.let { reason -> invalid(failureFor, reason) }
      return FeatureTaskRuntimeValidationGateExecutionEvidence(validationStatus, checks, gateRunCount, gateRuns)
    }

    private fun decodeChecks(
      raw: Map<String, Any?>,
      onInvalid: (String) -> SkillBillRuntimeException,
    ): List<String> {
      if (!raw.containsKey(ValidationEvidencePayloadKeys.CHECKS)) {
        invalid(onInvalid, "checks is missing.")
      }
      val checksRaw = raw[ValidationEvidencePayloadKeys.CHECKS]
      val list =
        checksRaw as? List<*>
          ?: invalid(onInvalid, "checks must be a list.")
      return list.mapIndexed { index, entry ->
        entry as? String ?: invalid(onInvalid, "checks[$index] must be a string.")
      }
    }

    private fun decodeGateRuns(
      raw: Any?,
      sourceLabel: String,
      onInvalid: (String) -> SkillBillRuntimeException,
      remapInvalid: (String, SkillBillRuntimeException) -> SkillBillRuntimeException,
    ): List<FeatureTaskRuntimeValidationGateRunRecord> {
      val runsRaw =
        raw as? List<*>
          ?: invalid(onInvalid, "gate_runs must be a list.")
      return runsRaw.mapIndexed { index, entry ->
        val map =
          entry as? Map<*, *>
            ?: invalid(onInvalid, "gate_runs[$index] must be a mapping.")
        val durationMs = readExecutionField(sourceLabel, remapInvalid) {
          map.gateProgressLong(ValidationEvidencePayloadKeys.DURATION_MS)
        }
        val outcomeWire = readExecutionField(sourceLabel, remapInvalid) {
          map.gateProgressString(ValidationEvidencePayloadKeys.OUTCOME)
        }
        val outcome = ValidationGateRunOutcome.fromWire(outcomeWire)
          ?: invalid(onInvalid, "Unknown validation gate outcome.")
        val cacheModeWire = readExecutionField(sourceLabel, remapInvalid) {
          map.gateProgressString(ValidationEvidencePayloadKeys.CACHE_MODE)
        }
        val cacheMode = ValidationGateCacheMode.fromWire(cacheModeWire)
          ?: invalid(onInvalid, "Unknown validation gate cache mode.")
        val executedWorkUnits = readExecutionField(sourceLabel, remapInvalid) {
          map.gateProgressInt(ValidationEvidencePayloadKeys.EXECUTED_WORK_UNITS)
        }
        val executedChecks = decodeGateRunExecutedChecks(map, onInvalid, index)
        val command = readExecutionField(sourceLabel, remapInvalid) {
          map.gateProgressOptionalString(ValidationEvidencePayloadKeys.COMMAND)
        }
        val exitCode = readExecutionField(sourceLabel, remapInvalid) {
          map.gateProgressOptionalInt(ValidationEvidencePayloadKeys.EXIT_CODE)
        }
        val repositoryCheckpoint = readExecutionField(sourceLabel, remapInvalid) {
          map.gateProgressOptionalString(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT)
        }
        val executedChecksRecorded = map.containsKey(ValidationEvidencePayloadKeys.EXECUTED_CHECKS)
        val reason = FeatureTaskRuntimeValidationGateRunRecord.executionViolation(
          durationMs, executedWorkUnits, command, exitCode, repositoryCheckpoint,
        ) ?: FeatureTaskRuntimeValidationGateRunRecord.checksViolation(
          outcome, exitCode, executedChecksRecorded, executedChecks,
        )
        if (reason != null) invalid(onInvalid, reason)
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs, outcome, cacheMode, executedWorkUnits, executedChecks, command,
          exitCode, repositoryCheckpoint, executedChecksRecorded,
        )
      }
    }

    private fun <T> readExecutionField(
      sourceLabel: String,
      remapInvalid: (String, SkillBillRuntimeException) -> SkillBillRuntimeException,
      read: () -> T,
    ): T = try {
      read()
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isInvalidWorkflowStateFailure())
      val reason = "Gate run execution fields are missing or malformed."
      val failure = invalidFeatureTaskRuntimeValidationEvidenceSchema(sourceLabel, reason)
      failure.addSuppressed(error)
      throw remapInvalid(reason, failure)
    }

    private fun decodeGateRunExecutedChecks(
      map: Map<*, *>,
      onInvalid: (String) -> SkillBillRuntimeException,
      index: Int,
    ): List<String> {
      if (!map.containsKey(ValidationEvidencePayloadKeys.EXECUTED_CHECKS)) return emptyList()
      val raw = map[ValidationEvidencePayloadKeys.EXECUTED_CHECKS]
      val list =
        raw as? List<*>
          ?: invalid(onInvalid, "gate_runs[$index].executed_checks must be a list.")
      return list.mapIndexed { checkIndex, entry ->
        entry as? String ?: invalid(
          onInvalid,
          "gate_runs[$index].executed_checks[$checkIndex] must be a string.",
        )
      }
    }

    private fun decodeRepositoryCheckpoint(
      raw: Any?,
      onInvalid: (String) -> SkillBillRuntimeException,
    ): String {
      val checkpoint =
        raw as? Map<*, *>
          ?: invalid(onInvalid, "repository_checkpoint must be a mapping.")
      val fingerprint = checkpoint[ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT] as? String
      if (fingerprint.isNullOrBlank()) {
        invalid(onInvalid, "repository_checkpoint.fingerprint must be non-blank.")
      }
      return fingerprint
    }

    private fun invalid(
      onInvalid: (String) -> SkillBillRuntimeException,
      reason: String,
    ): Nothing = throw onInvalid(reason)

    private fun invalid(
      sourceLabel: String,
      reason: String,
    ): Nothing = throw invalidFeatureTaskRuntimeValidationEvidenceSchema(sourceLabel, reason)
  }
}
