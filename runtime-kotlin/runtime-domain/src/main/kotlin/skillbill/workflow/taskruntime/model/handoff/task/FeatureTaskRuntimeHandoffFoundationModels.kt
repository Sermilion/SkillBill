package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_DIAGNOSTIC_DEGRADATION_MEASUREMENT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_REJECTION_MEASUREMENT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass

data class FeatureTaskRuntimeProducerIteration(
  val phaseId: String,
  val iteration: Int,
) {
  init {
    val reason = violation(phaseId, iteration)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    internal fun violation(phaseId: String, iteration: Int): String? =
      when {
        phaseId.isBlank() -> "FeatureTaskRuntimeProducerIteration.phaseId must be non-blank."
        iteration < 1 -> "FeatureTaskRuntimeProducerIteration.iteration must be >= 1."
        else -> null
      }
  }
}

data class FeatureTaskRuntimeProjectionMeasurement(
  val workflowId: String,
  val consumerPhaseId: String,
  val projectionContractId: String,
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpointFingerprint: String,
  val projectedUtf8Bytes: Int,
  val projectedCollectionItems: Int,
  val estimatedTokens: Int,
  val privateEvidenceUtf8Bytes: Int,
  val deliveredProjectionUtf8Bytes: Int,
  val failureClassification: FeatureTaskRuntimeProjectionFailureClassification? = null,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeProjectionMeasurement.workflowId must be non-blank." }
    require(consumerPhaseId.isNotBlank()) {
      "FeatureTaskRuntimeProjectionMeasurement.consumerPhaseId must be non-blank."
    }
    require(projectionContractId.isNotBlank()) {
      "FeatureTaskRuntimeProjectionMeasurement.projectionContractId must be non-blank."
    }
    require(repositoryCheckpointFingerprint.isNotBlank()) {
      "FeatureTaskRuntimeProjectionMeasurement.repositoryCheckpointFingerprint must be non-blank."
    }
    require(
      projectedUtf8Bytes >= 0 &&
        projectedCollectionItems >= 0 &&
        estimatedTokens >= 0 &&
        privateEvidenceUtf8Bytes >= 0 &&
        deliveredProjectionUtf8Bytes >= 0,
    ) {
      "FeatureTaskRuntimeProjectionMeasurement counts must be non-negative."
    }
  }

  internal fun toTelemetryMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PROJECTION_MEASUREMENT_CONTRACT_VERSION,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      "consumer_phase_id" to consumerPhaseId,
      "projection_contract_id" to projectionContractId,
      "producer_iteration" to
        mapOf(
          SharedPayloadKeys.PHASE_ID to producerIteration.phaseId,
          "iteration" to producerIteration.iteration,
        ),
      "repository_checkpoint_fingerprint" to repositoryCheckpointFingerprint,
      "projected_utf8_bytes" to projectedUtf8Bytes,
      "projected_collection_items" to projectedCollectionItems,
      "estimated_tokens" to estimatedTokens,
      "private_evidence_utf8_bytes" to privateEvidenceUtf8Bytes,
      "delivered_projection_utf8_bytes" to deliveredProjectionUtf8Bytes,
    ).apply {
      failureClassification?.let { put("failure_classification", it.wireValue) }
    }
}

data class FeatureTaskRuntimeSharedEvidenceMeasurement(
  val workflowId: String,
  val checkpointFingerprint: String,
  val consumerPhaseId: String,
  val outcome: FeatureTaskRuntimeSharedEvidenceOutcome,
  val fileIndexCount: Int,
  val hunkIndexCount: Int,
) {
  init {
    require(workflowId.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement.workflowId must be non-blank."
    }
    require(checkpointFingerprint.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement.checkpointFingerprint must be non-blank."
    }
    require(consumerPhaseId.isNotBlank()) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement.consumerPhaseId must be non-blank."
    }
    require(fileIndexCount >= 0 && hunkIndexCount >= 0) {
      "FeatureTaskRuntimeSharedEvidenceMeasurement counts must be non-negative."
    }
  }

  internal fun toTelemetryMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_SHARED_EVIDENCE_PROJECTION_CONTRACT_VERSION,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      "checkpoint_fingerprint" to checkpointFingerprint,
      "consumer_phase_id" to consumerPhaseId,
      "outcome" to outcome.wireValue,
      "file_index_count" to fileIndexCount,
      "hunk_index_count" to hunkIndexCount,
    )
}

enum class FeatureTaskRuntimeSharedEvidenceOutcome(val wireValue: String) {
  DERIVATION("derivation"),
  REUSE("reuse"),
  CHECKPOINT_CHANGE_REDERIVATION("checkpoint_change_rederivation"),
}

data class FeatureTaskRuntimeRejectionMeasurement(
  val workflowId: String,
  val phaseId: String,
  val iteration: Int,
  val rule: String,
  val pointerPath: String,
  val violationClass: FeatureTaskRuntimeRejectionViolationClass,
  val declaredCap: Int? = null,
  val observedLength: Int? = null,
  val exhaustedFixLoop: Boolean? = null,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeRejectionMeasurement.workflowId must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeRejectionMeasurement.phaseId must be non-blank." }
    require(iteration >= 1) { "FeatureTaskRuntimeRejectionMeasurement.iteration must be >= 1." }
    require(rule.isNotBlank()) { "FeatureTaskRuntimeRejectionMeasurement.rule must be non-blank." }
    require(pointerPath.isNotBlank()) {
      "FeatureTaskRuntimeRejectionMeasurement.pointerPath must be non-blank."
    }
    require(declaredCap == null || declaredCap >= 0) {
      "FeatureTaskRuntimeRejectionMeasurement.declaredCap must be non-negative."
    }
    require(observedLength == null || observedLength >= 0) {
      "FeatureTaskRuntimeRejectionMeasurement.observedLength must be non-negative."
    }
  }

  private fun exhaustedFixLoopAvailability(): TelemetryMeasurementAvailability =
    when (exhaustedFixLoop) {
      null -> TelemetryMeasurementAvailability.UNAVAILABLE_UNSUPPORTED
      else -> TelemetryMeasurementAvailability.MEASURED
    }

  internal fun toTelemetryMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_REJECTION_MEASUREMENT_CONTRACT_VERSION,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      SharedPayloadKeys.PHASE_ID to phaseId,
      "iteration" to iteration,
      "rule" to rule,
      "pointer_path" to pointerPath,
      "violation_class" to violationClass.wireValue,
      "exhausted_fix_loop_availability" to exhaustedFixLoopAvailability().wireValue,
      "exhausted_fix_loop" to exhaustedFixLoop,
    ).apply {
      declaredCap?.let { put("declared_cap", it) }
      observedLength?.let { put("observed_length", it) }
    }
}

data class FeatureTaskRuntimeDiagnosticDegradationMeasurement(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val repairTurn: Int? = null,
  val generation: Int,
  val operation: String,
  val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  val conflictingKey: String,
) {
  init {
    require(workflowId.isNotBlank()) {
      "FeatureTaskRuntimeDiagnosticDegradationMeasurement.workflowId must be non-blank."
    }
    require(phaseId.isNotBlank()) {
      "FeatureTaskRuntimeDiagnosticDegradationMeasurement.phaseId must be non-blank."
    }
    require(attempt >= 0) { "FeatureTaskRuntimeDiagnosticDegradationMeasurement.attempt must be >= 0." }
    require(repairTurn == null || repairTurn >= 0) {
      "FeatureTaskRuntimeDiagnosticDegradationMeasurement.repairTurn must be >= 0 when present."
    }
    require(generation >= 0) {
      "FeatureTaskRuntimeDiagnosticDegradationMeasurement.generation must be >= 0."
    }
    require(operation.isNotBlank()) {
      "FeatureTaskRuntimeDiagnosticDegradationMeasurement.operation must be non-blank."
    }
    require(conflictingKey.isNotBlank()) {
      "FeatureTaskRuntimeDiagnosticDegradationMeasurement.conflictingKey must be non-blank."
    }
  }

  internal fun toTelemetryMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_DIAGNOSTIC_DEGRADATION_MEASUREMENT_CONTRACT_VERSION,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      SharedPayloadKeys.PHASE_ID to phaseId,
      "attempt" to attempt,
      "generation" to generation,
      "operation" to operation,
      "failure_class" to failureClass.wireValue,
      "conflicting_key" to conflictingKey,
    ).apply {
      repairTurn?.let { put("repair_turn", it) }
    }
}

enum class FeatureTaskRuntimeRejectionViolationClass(val wireValue: String) {
  LENGTH("length"),
  MISSING("missing"),
  TYPE("type"),
  CONST("const"),
  MALFORMED("malformed"),
  OTHER("other"),
}

private val REJECTION_LENGTH_PATTERN =
  Regex("""(?:must be|allows) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)

fun featureTaskRuntimeRejectionCapOf(validationReason: String): Int? =
  REJECTION_LENGTH_PATTERN.find(validationReason)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

fun featureTaskRuntimeRejectionViolationClassOf(validationReason: String): FeatureTaskRuntimeRejectionViolationClass =
  when {
    featureTaskRuntimeRejectionCapOf(validationReason) != null || validationReason.contains("maxLength") ->
      FeatureTaskRuntimeRejectionViolationClass.LENGTH
    validationReason.contains("is malformed") || validationReason.contains("must be an object") ->
      FeatureTaskRuntimeRejectionViolationClass.MALFORMED
    validationReason.contains("must be the constant value") ->
      FeatureTaskRuntimeRejectionViolationClass.CONST
    validationReason.contains("is missing") || validationReason.contains("is not defined in the schema") ->
      FeatureTaskRuntimeRejectionViolationClass.MISSING
    validationReason.contains("expected") || validationReason.contains("must be a") ->
      FeatureTaskRuntimeRejectionViolationClass.TYPE
    else -> FeatureTaskRuntimeRejectionViolationClass.OTHER
  }

enum class FeatureTaskRuntimeProjectionFailureClassification(val wireValue: String) {
  INVALID_CONTRACT("invalid_contract"),
  UNSUPPORTED_VERSION("unsupported_version"),
  UNPROJECTABLE_SOURCE("unprojectable_source"),
  BUDGET_OVERFLOW("budget_overflow"),
  STALE_CHECKPOINT("stale_checkpoint"),
  STALE_PRODUCER_ITERATION("stale_producer_iteration"),
  SIBLING_CONTEXT("sibling_context"),
}
