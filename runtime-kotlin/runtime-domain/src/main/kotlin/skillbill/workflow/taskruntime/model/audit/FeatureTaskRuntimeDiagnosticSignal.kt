package skillbill.workflow.taskruntime.model.audit

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass

private const val FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_LIMIT: Int = 32

data class FeatureTaskRuntimeDiagnosticSignal(
  val operation: String,
  val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  val conflictingKey: String,
  val phaseId: String,
  val attempt: Int,
  val repairTurn: Int?,
  val generation: Int,
  val recordedAt: String,
) {
  init {
    require(operation.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.operation must be non-blank." }
    require(conflictingKey.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.conflictingKey must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.phaseId must be non-blank." }
    require(attempt >= 0) { "FeatureTaskRuntimeDiagnosticSignal.attempt must be >= 0." }
    require(repairTurn == null || repairTurn >= 0) {
      "FeatureTaskRuntimeDiagnosticSignal.repairTurn must be >= 0 when present."
    }
    require(generation >= 0) { "FeatureTaskRuntimeDiagnosticSignal.generation must be >= 0." }
    require(recordedAt.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.recordedAt must be non-blank." }
  }

  fun operatorSummary(): String =
    "Diagnostic evidence write '$operation' failed as '${failureClass.wireValue}' for key " +
      "'$conflictingKey' (phase '$phaseId', attempt $attempt, repair turn ${repairTurn ?: "any"}, " +
      "generation $generation). The evidence was not retained; the run continued."

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      "operation" to operation,
      "failure_class" to failureClass.wireValue,
      "conflicting_key" to conflictingKey,
      SharedPayloadKeys.PHASE_ID to phaseId,
      "attempt" to attempt,
      "repair_turn" to repairTurn,
      "generation" to generation,
      "recorded_at" to recordedAt,
    )

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeDiagnosticSignal {
      val reader = durableArtifactMapReader(raw)
      return FeatureTaskRuntimeDiagnosticSignal(
        operation = reader.requiredString("operation"),
        failureClass = FeatureTaskRuntimeDiagnosticFailureClass.fromWire(reader.requiredString("failure_class")),
        conflictingKey = reader.requiredString("conflicting_key"),
        phaseId = reader.requiredString(SharedPayloadKeys.PHASE_ID),
        attempt = reader.requiredInt("attempt"),
        repairTurn = reader.optionalInt("repair_turn"),
        generation = reader.requiredInt("generation"),
        recordedAt = reader.requiredString("recorded_at"),
      )
    }
  }
}

internal fun featureTaskRuntimeDiagnosticSignalsFromWire(raw: Any?): List<FeatureTaskRuntimeDiagnosticSignal> {
  if (raw == null) return emptyList()
  val entries =
    raw as? List<*>
      ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime diagnostic signals must be an array.")
  return entries.map { entry ->
    FeatureTaskRuntimeDiagnosticSignal.fromArtifactMap(
      JsonCodec.anyToStringAnyMap(entry)
        ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime diagnostic signal must be an object."),
    )
  }
}

fun featureTaskRuntimeAppendDiagnosticSignal(
  existing: List<FeatureTaskRuntimeDiagnosticSignal>,
  signal: FeatureTaskRuntimeDiagnosticSignal,
): List<FeatureTaskRuntimeDiagnosticSignal> =
  (existing + signal).takeLast(FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_LIMIT)
