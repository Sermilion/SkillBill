package skillbill.goalrunner.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.time.parsePersistedInstant
import java.time.Instant

const val GOAL_ATTEMPT_LEDGER_LIMIT: Int = 200

enum class GoalAttemptLedgerAction(val wireValue: String) {
  CHILD_ACTIVATION("child_activation"),
  RESUME("resume"),
  RETRY("retry"),
  TERMINAL_DONE_CHECK("terminal_done_check"),
  POLICY_BLOCK("policy_block"),
  TIMEOUT("timeout"),
  INTERRUPTION("interruption"),
  FINAL_RECONCILED_OUTCOME("final_reconciled_outcome"),
  DIAGNOSTIC_INSPECTION("diagnostic_inspection"),

  BACKWARD_EDGE_ENTRY("backward_edge_entry"),
  ;

  companion object {
    fun fromWire(value: String): GoalAttemptLedgerAction =
      entries.firstOrNull { it.wireValue == value }
        ?: throw InvalidWorkflowStateSchemaError("Unknown goal attempt ledger action '$value'.")
  }
}

sealed interface GoalAttemptLaunchOutcome {
  val wireValue: String

  data object SpawnFailed : GoalAttemptLaunchOutcome {
    override val wireValue: String = "spawn_failed"
  }

  data object TimedOut : GoalAttemptLaunchOutcome {
    override val wireValue: String = "timed_out"
  }

  data object Interrupted : GoalAttemptLaunchOutcome {
    override val wireValue: String = "interrupted"
  }

  data class Exited(val statusCode: Int?) : GoalAttemptLaunchOutcome {
    override val wireValue: String = if (statusCode == 0) "exited_ok" else "exited_${statusCode ?: "unknown"}"
  }

  companion object {
    fun fromWire(value: String): GoalAttemptLaunchOutcome? =
      when (value) {
        "spawn_failed" -> SpawnFailed
        "timed_out" -> TimedOut
        "interrupted" -> Interrupted
        "exited_ok" -> Exited(0)
        "exited_unknown" -> Exited(null)
        else -> value.removePrefix("exited_").toIntOrNull()?.let(::Exited)
      }
  }
}

data class GoalAttemptLedgerEntry(
  val action: GoalAttemptLedgerAction,
  val sequenceNumber: Int,
  val timestamp: Instant,
  val issueKey: String? = null,
  val subtaskId: Int? = null,
  val previousWorkflowId: String? = null,
  val previousStatus: WorkflowStatus? = null,
  val previousStep: String? = null,
  val blockedReason: String? = null,
  val latestLiveness: String? = null,
  val launchOutcome: GoalAttemptLaunchOutcome? = null,
  val timedOut: Boolean? = null,
  val interrupted: Boolean? = null,
  val childSessionPath: String? = null,
  val childSessionId: String? = null,
  val finalReconciledResult: String? = null,
  val stopReason: String? = null,
  val diagnosticClass: String? = null,
  val currentStep: String? = null,
  val exitStatus: Int? = null,
  val recoverableJsonPresent: Boolean? = null,
  val nextSafeAction: String? = null,
  val loopId: String? = null,
  val cumulativeLoopCount: Int? = null,
  val attemptDurationMillis: Long? = null,
  val causingLoopEntry: String? = null,
  val reAttemptCause: String? = null,
  val findingsInScope: Int? = null,
) {
  constructor(
    action: GoalAttemptLedgerAction,
    sequenceNumber: Int,
    timestamp: String,
    issueKey: String? = null,
    subtaskId: Int? = null,
    previousWorkflowId: String? = null,
    previousStatus: WorkflowStatus? = null,
    previousStep: String? = null,
    blockedReason: String? = null,
    latestLiveness: String? = null,
    launchOutcome: GoalAttemptLaunchOutcome? = null,
    timedOut: Boolean? = null,
    interrupted: Boolean? = null,
    childSessionPath: String? = null,
    childSessionId: String? = null,
    finalReconciledResult: String? = null,
    stopReason: String? = null,
    diagnosticClass: String? = null,
    currentStep: String? = null,
    exitStatus: Int? = null,
    recoverableJsonPresent: Boolean? = null,
    nextSafeAction: String? = null,
    loopId: String? = null,
    cumulativeLoopCount: Int? = null,
    attemptDurationMillis: Long? = null,
    causingLoopEntry: String? = null,
    reAttemptCause: String? = null,
    findingsInScope: Int? = null,
  ) : this(
    action, sequenceNumber, parsePersistedInstant(timestamp), issueKey, subtaskId, previousWorkflowId,
    previousStatus, previousStep, blockedReason, latestLiveness, launchOutcome, timedOut, interrupted,
    childSessionPath, childSessionId, finalReconciledResult, stopReason, diagnosticClass, currentStep,
    exitStatus, recoverableJsonPresent, nextSafeAction, loopId, cumulativeLoopCount, attemptDurationMillis,
    causingLoopEntry, reAttemptCause, findingsInScope,
  )

  init {
    require(sequenceNumber >= 0) { "GoalAttemptLedgerEntry.sequenceNumber must be non-negative." }
  }

  fun toPersistenceWire(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

  internal fun toArtifactMap(): Map<String, Any?> {
    val optional =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.ISSUE_KEY to issueKey,
        SharedPayloadKeys.SUBTASK_ID to subtaskId,
        "previous_workflow_id" to previousWorkflowId,
        "previous_status" to previousStatus?.wireValue,
        "previous_step" to previousStep,
        "blocked_reason" to blockedReason,
        "latest_liveness" to latestLiveness,
        "launch_outcome" to launchOutcome?.wireValue,
        "timed_out" to timedOut,
        "interrupted" to interrupted,
        "child_session_path" to childSessionPath,
        "child_session_id" to childSessionId,
        "final_reconciled_result" to finalReconciledResult,
        "stop_reason" to stopReason,
        "diagnostic_class" to diagnosticClass,
        "current_step" to currentStep,
        "exit_status" to exitStatus,
        "recoverable_json_present" to recoverableJsonPresent,
        "next_safe_action" to nextSafeAction,
        "loop_id" to loopId,
        "cumulative_loop_count" to cumulativeLoopCount,
        "attempt_duration_millis" to attemptDurationMillis,
        "causing_loop_entry" to causingLoopEntry,
        "re_attempt_cause" to reAttemptCause,
        "findings_in_scope" to findingsInScope,
      )
    return linkedMapOf<String, Any?>(
      "action" to action.wireValue,
      "sequence_number" to sequenceNumber,
      "timestamp" to timestamp.toString(),
    ).apply { putAll(optional.filterValues { it != null }) }
  }
}

data class GoalAttemptLedger(
  val entries: List<GoalAttemptLedgerEntry> = emptyList(),
  val retentionLimit: Int = GOAL_ATTEMPT_LEDGER_LIMIT,
) {
  fun append(entry: GoalAttemptLedgerEntry): GoalAttemptLedger =
    copy(entries = (entries + entry).sortedBy(GoalAttemptLedgerEntry::sequenceNumber).takeLast(retentionLimit))

  internal fun toArtifactList(): List<Map<String, Any?>> = entries.map(GoalAttemptLedgerEntry::toArtifactMap)
}
