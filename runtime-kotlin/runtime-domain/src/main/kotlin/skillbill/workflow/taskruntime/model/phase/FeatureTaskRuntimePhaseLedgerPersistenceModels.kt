package skillbill.workflow.taskruntime.model.phase

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.time.parsePersistedInstant
import java.time.Instant

enum class FeatureTaskRuntimePhaseExecutionOrigin(val wireValue: String) {
  AGENT_EXECUTED("agent-executed"),
  GOAL_PLANNING_HYDRATED("goal-planning-hydrated"),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskRuntimePhaseExecutionOrigin =
      entries.firstOrNull { it.wireValue == value }
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime artifact field 'execution_origin' has unsupported value '$value'.",
        )
  }
}

enum class FeatureTaskRuntimeFailureDisposition(val wireValue: String, val retryOnResume: Boolean) {
  RETRYABLE("retryable", true),
  NON_RETRYABLE_POLICY_CONFLICT("non_retryable_policy_conflict", false),
  NEEDS_USER_ACTION("needs_user_action", false),
  PROCESS_FAILURE("process_failure", true),
  INVALID_OUTPUT("invalid_output", true),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskRuntimeFailureDisposition? =
      entries.firstOrNull { it.wireValue == value }
  }
}

enum class FeatureTaskRuntimePhaseLedgerAction(val wireValue: String) {
  START("start"),
  RESUME("resume"),
  RETRY("retry"),
  FIX_LOOP_ITERATION("fix_loop_iteration"),
  LOOP_EDGE("loop_edge"),
  LOOP_CAP_EXHAUSTED("loop_cap_exhausted"),
  BLOCKED("blocked"),
  PAUSED("paused"),
  COMPLETE("complete"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseLedgerAction =
      entries.firstOrNull { it.wireValue == value }
        ?: throw InvalidWorkflowStateSchemaError(
          "Unknown feature-task-runtime phase ledger action '$value'. " +
            "Allowed: ${entries.joinToString { it.wireValue }}.",
        )
  }
}

data class FeatureTaskRuntimePhaseLedgerEntry(
  val action: FeatureTaskRuntimePhaseLedgerAction,
  val sequenceNumber: Int,
  val timestamp: Instant,
  val phaseId: String,
  val attemptCount: Int,
  val resolvedAgentId: String? = null,
  val executionOrigin: FeatureTaskRuntimePhaseExecutionOrigin =
    FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
  val fixLoopIteration: Int? = null,
  val blockedReason: String? = null,
  val loopId: String? = null,
  val edgeIteration: Int? = null,
) {
  constructor(
    action: FeatureTaskRuntimePhaseLedgerAction,
    sequenceNumber: Int,
    timestamp: String,
    phaseId: String,
    attemptCount: Int,
    resolvedAgentId: String? = null,
    executionOrigin: FeatureTaskRuntimePhaseExecutionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
    fixLoopIteration: Int? = null,
    blockedReason: String? = null,
    loopId: String? = null,
    edgeIteration: Int? = null,
  ) : this(
    action,
    sequenceNumber,
    parsePersistedInstant(timestamp),
    phaseId,
    attemptCount,
    resolvedAgentId,
    executionOrigin,
    fixLoopIteration,
    blockedReason,
    loopId,
    edgeIteration,
  )

  init {
    require(sequenceNumber >= 0) {
      "FeatureTaskRuntimePhaseLedgerEntry.sequenceNumber must be non-negative, was $sequenceNumber."
    }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseLedgerEntry.phaseId must be non-blank." }
    require(attemptCount >= 1) {
      "FeatureTaskRuntimePhaseLedgerEntry.attemptCount must be >= 1, was $attemptCount."
    }
    fixLoopIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseLedgerEntry.fixLoopIteration must be >= 1 when present, was $iteration."
      }
    }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseLedgerEntry.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      DecompositionManifestPayloadKeys.ACTION to action.wireValue,
      FeatureTaskRuntimePhasePayloadKeys.SEQUENCE_NUMBER to sequenceNumber,
      "timestamp" to timestamp.toString(),
      SharedPayloadKeys.PHASE_ID to phaseId,
      "attempt_count" to attemptCount,
    ).apply {
      resolvedAgentId?.let { put(FeatureTaskRuntimePhasePayloadKeys.RESOLVED_AGENT_ID, it) }
      put(FeatureTaskRuntimePhasePayloadKeys.EXECUTION_ORIGIN, executionOrigin.wireValue)
      fixLoopIteration?.let { put("fix_loop_iteration", it) }
      blockedReason?.let { put(DecompositionManifestPayloadKeys.BLOCKED_REASON, it) }
      loopId?.let { put("loop_id", it) }
      edgeIteration?.let { put("edge_iteration", it) }
    }

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseLedgerEntry {
      val reader = durableArtifactMapReader(raw)
      val attemptCount = reader.requiredInt("attempt_count")
      if (attemptCount < 1) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime phase ledger entry attempt_count must be >= 1, was $attemptCount.",
        )
      }
      return try {
        FeatureTaskRuntimePhaseLedgerEntry(
          action =
            FeatureTaskRuntimePhaseLedgerAction.fromWire(
              reader.requiredString(DecompositionManifestPayloadKeys.ACTION),
            ),
          sequenceNumber = reader.requiredInt(FeatureTaskRuntimePhasePayloadKeys.SEQUENCE_NUMBER),
          timestamp = parsePersistedInstant(reader.requiredString("timestamp")),
          phaseId =
            requireKnownFeatureTaskRuntimePhaseId(
              reader.requiredString(SharedPayloadKeys.PHASE_ID),
              SharedPayloadKeys.PHASE_ID,
            ),
          attemptCount = attemptCount,
          resolvedAgentId = reader.optionalString(FeatureTaskRuntimePhasePayloadKeys.RESOLVED_AGENT_ID),
          executionOrigin =
            reader.optionalString(FeatureTaskRuntimePhasePayloadKeys.EXECUTION_ORIGIN)?.let(
              FeatureTaskRuntimePhaseExecutionOrigin::fromWireValue,
            ) ?: FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
          fixLoopIteration = reader.optionalInt("fix_loop_iteration"),
          blockedReason = reader.optionalString(DecompositionManifestPayloadKeys.BLOCKED_REASON),
          loopId = reader.optionalString("loop_id"),
          edgeIteration = reader.optionalInt("edge_iteration"),
        )
      } catch (error: IllegalArgumentException) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime phase ledger entry is invalid.",
          error,
        )
      }
    }
  }
}
