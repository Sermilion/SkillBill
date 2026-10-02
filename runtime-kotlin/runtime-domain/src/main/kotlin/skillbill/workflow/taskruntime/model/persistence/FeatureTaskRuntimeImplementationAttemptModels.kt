package skillbill.workflow.taskruntime.model.persistence

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.appendBoundedHistoryBySequence
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.model.core.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

data class FeatureTaskRuntimeImplementationAttempt(
  val sequenceNumber: Int,
  val phaseId: String,
  val attemptNumber: Int,
  val agentId: String,
  val status: FeatureTaskRuntimeImplementationAttemptStatus,
  val recordedAt: String,
  val value: String,
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val prompt: String? = null,
) {
  init {
    require(sequenceNumber >= 0) {
      "FeatureTaskRuntimeImplementationAttempt.sequenceNumber must be non-negative, was $sequenceNumber."
    }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.phaseId must be non-blank." }
    require(attemptNumber >= 1) {
      "FeatureTaskRuntimeImplementationAttempt.attemptNumber must be >= 1, was $attemptNumber."
    }
    require(agentId.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.agentId must be non-blank." }
    require(recordedAt.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.recordedAt must be non-blank." }
    require(value.isNotBlank()) { "FeatureTaskRuntimeImplementationAttempt.value must be non-blank." }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimeImplementationAttempt.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
  }

  val carriesOpenObligation: Boolean
    get() = status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "sequence_number" to sequenceNumber,
      SharedPayloadKeys.PHASE_ID to phaseId,
      "attempt_number" to attemptNumber,
      "agent_id" to agentId,
      SharedPayloadKeys.STATUS to status.wireValue,
      "recorded_at" to recordedAt,
      SharedPayloadKeys.VALUE to value,
    ).apply {
      loopId?.let { put("loop_id", it) }
      edgeIteration?.let { put("edge_iteration", it) }
      failureDisposition?.let { put(SharedPayloadKeys.FAILURE_DISPOSITION, it.wireValue) }
      prompt?.let { put(SharedPayloadKeys.PROMPT, it) }
    }

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeImplementationAttempt {
      val unexpected = raw.keys - ALLOWED_FIELDS
      if (unexpected.isNotEmpty()) {
        implementationAttemptError(
          "Feature-task-runtime implementation-attempt entry carries unsupported fields; the store is " +
            "quarantined and regenerated rather than reinterpreted.",
        )
      }
      val reader = durableArtifactMapReader(raw)
      return FeatureTaskRuntimeImplementationAttempt(
        sequenceNumber = reader.requiredInt("sequence_number"),
        phaseId = reader.requiredString(SharedPayloadKeys.PHASE_ID),
        attemptNumber =
          reader.requiredInt("attempt_number").also { attempt ->
            if (attempt < 1) {
              implementationAttemptError(
                "Feature-task-runtime implementation-attempt attempt_number must be >= 1, was $attempt.",
              )
            }
          },
        agentId = reader.requiredString("agent_id"),
        status =
          FeatureTaskRuntimeImplementationAttemptStatus.fromWireValue(
            reader.requiredString(SharedPayloadKeys.STATUS),
          ),
        recordedAt = reader.requiredString("recorded_at"),
        value = reader.requiredString(SharedPayloadKeys.VALUE),
        loopId = reader.optionalString("loop_id"),
        edgeIteration = reader.optionalInt("edge_iteration"),
        failureDisposition =
          reader.optionalString(SharedPayloadKeys.FAILURE_DISPOSITION)?.let { value ->
            FeatureTaskRuntimeFailureDisposition.fromWireValue(value)
              ?: implementationAttemptError(
                "Feature-task-runtime implementation-attempt 'failure_disposition' has unsupported value.",
              )
          },
        prompt = reader.optionalString(SharedPayloadKeys.PROMPT),
      )
    }

    private val ALLOWED_FIELDS =
      setOf(
        "sequence_number",
        SharedPayloadKeys.PHASE_ID,
        "attempt_number",
        "agent_id",
        SharedPayloadKeys.STATUS,
        "recorded_at",
        SharedPayloadKeys.VALUE,
        "loop_id",
        "edge_iteration",
        SharedPayloadKeys.FAILURE_DISPOSITION,
        SharedPayloadKeys.PROMPT,
      )
  }
}

enum class FeatureTaskRuntimeImplementationAttemptStatus(val wireValue: String) {
  COMPLETED("completed"),
  BLOCKED("blocked"),
  FAILED("failed"),
  INCOMPLETE("incomplete"),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskRuntimeImplementationAttemptStatus =
      entries.firstOrNull { it.wireValue == value }
        ?: implementationAttemptError(
          "Feature-task-runtime implementation-attempt 'status' has unsupported value '$value'.",
        )
  }
}

internal fun featureTaskRuntimeImplementationAttemptRecordToWire(
  attempts: List<FeatureTaskRuntimeImplementationAttempt>,
): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION,
    "attempts" to attempts.map { it.toArtifactMap() },
  )

internal fun featureTaskRuntimeImplementationAttemptsFromWire(
  raw: Any?,
): List<FeatureTaskRuntimeImplementationAttempt> {
  val map =
    JsonCodec.anyToStringAnyMap(raw)
      ?: implementationAttemptError("Feature-task-runtime implementation-attempt record must be an object.")
  val reader = durableArtifactMapReader(map)
  val version = reader.requiredString(SharedPayloadKeys.CONTRACT_VERSION)
  if (version != FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION) {
    implementationAttemptError(
      "Feature-task-runtime implementation-attempt record uses unsupported contract version '$version'; " +
        "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
    )
  }
  return reader.requiredList("attempts").map { entry ->
    FeatureTaskRuntimeImplementationAttempt.fromArtifactMap(
      JsonCodec.anyToStringAnyMap(entry)
        ?: implementationAttemptError("Feature-task-runtime implementation-attempt entry must be an object."),
    )
  }
}

fun featureTaskRuntimeAppendImplementationAttempt(
  existing: List<FeatureTaskRuntimeImplementationAttempt>,
  entry: FeatureTaskRuntimeImplementationAttempt,
  retentionLimit: Int = FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS_LIMIT,
): List<FeatureTaskRuntimeImplementationAttempt> {
  val ordered =
    appendBoundedHistoryBySequence(
      existing = existing.map { it.toArtifactMap() },
      entry = entry.toArtifactMap(),
      retentionLimit = Int.MAX_VALUE,
    ).map { raw ->
      FeatureTaskRuntimeImplementationAttempt.fromArtifactMap(
        JsonCodec.anyToStringAnyMap(raw)
          ?: implementationAttemptError("Implementation attempt history entry must decode to an object."),
      )
    }
  if (ordered.size <= retentionLimit) return ordered
  val overflow = ordered.size - retentionLimit
  val droppableIndices = ordered.indices.filterNot { ordered[it].carriesOpenObligation }.take(overflow)
  val dropped = if (droppableIndices.size == overflow) droppableIndices.toSet() else (0 until overflow).toSet()
  return ordered.filterIndexed { index, _ -> index !in dropped }
}

private fun implementationAttemptError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)
