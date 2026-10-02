package skillbill.goalrunner.model

import skillbill.workflow.decomposition.model.DecompositionSubtask

enum class GoalRunnerTerminalStatus(val wireValue: String) {
  COMPLETE("complete"),
  FAILED("failed"),
  BLOCKED("blocked"),
  TIMEOUT("timeout"),
  NO_TERMINAL_STORE_OUTCOME("no_terminal_store_outcome"),

  RECONCILABLE("reconcilable"),

  PAUSED("paused"),

  ;

  companion object {
    fun fromWire(value: String): GoalRunnerTerminalStatus? =
      when (value) {
        "complete", "completed" -> COMPLETE
        "timeout", "timed_out" -> TIMEOUT
        else -> entries.firstOrNull { it.wireValue == value }
      }
  }
}

enum class GoalRunnerStopReason {
  FAILED,
  BLOCKED,
  POLICY_BLOCKED,
  INTERRUPTED,
  TIMEOUT,
  NO_TERMINAL_STORE_OUTCOME,
  PULL_REQUEST_FAILED,
  DEPENDENCIES_BLOCKED,

  RECONCILED_RESUMABLE,

  AWAITING_OPERATOR_DECISION,

  PAUSED,
  ;

  companion object {
    val RESUMABLE_STOP_REASONS = setOf(RECONCILED_RESUMABLE, AWAITING_OPERATOR_DECISION, PAUSED)
  }
}

data class GoalRunnerStoredOutcome(
  val status: GoalRunnerTerminalStatus,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String? = null,
  val suppressPr: Boolean,
)

sealed interface GoalRunnerReconciledOutcome {
  data class Complete(
    val workflowId: String,
    val commitSha: String,
    val lastResumableStep: String,
  ) : GoalRunnerReconciledOutcome

  data class Stop(
    val reason: GoalRunnerStopReason,
    val blockedReason: String,
    val workflowId: String?,
    val commitSha: String?,
    val lastResumableStep: String,
    val liveness: GoalRunnerLivenessSnapshot? = null,
  ) : GoalRunnerReconciledOutcome
}

data class GoalRunnerSubtaskDecision(
  val subtask: DecompositionSubtask,
  val action: GoalRunnerSubtaskAction,
)

enum class GoalRunnerSubtaskAction {
  START,
  RESUME,
}

sealed interface GoalRunnerSelection {
  data class Run(val decision: GoalRunnerSubtaskDecision) : GoalRunnerSelection

  data class Blocked(val subtask: DecompositionSubtask, val reason: String) : GoalRunnerSelection

  data object Done : GoalRunnerSelection
}

data class GoalRunnerStopReport(
  val issueKey: String,
  val subtaskId: Int,
  val reason: GoalRunnerStopReason,
  val blockedReason: String,
  val workflowId: String?,
  val lastResumableStep: String,
)

enum class GoalPullRequestStatus(val wireValue: String) {
  OPENED("opened"),
  EXISTING("existing"),
  ;

  companion object {
    fun fromWire(value: String): GoalPullRequestStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

sealed interface GoalRunnerRunReport {
  val issueKey: String
  val attemptedSubtasks: List<Int>
  val parentWorkflowId: String?
    get() = null

  data class Completed(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val pullRequestUrl: String?,
    val pullRequestStatus: GoalPullRequestStatus,
    val subtasksCompleted: Int,
    val subtasksPending: Int,
    val subtasksBlocked: Int,
    val unaddressedFindingCount: Int? = 0,
    val unaddressedSeverityBreakdown: Map<String, Int> = emptyMap(),
    val featureName: String? = null,
    override val parentWorkflowId: String? = null,
  ) : GoalRunnerRunReport

  data class Stopped(
    override val issueKey: String,
    override val attemptedSubtasks: List<Int>,
    val stop: GoalRunnerStopReport,
    override val parentWorkflowId: String? = null,
  ) : GoalRunnerRunReport
}

fun GoalRunnerStopReason.toLedgerAction(): GoalAttemptLedgerAction =
  when (this) {
    GoalRunnerStopReason.TIMEOUT -> GoalAttemptLedgerAction.TIMEOUT
    GoalRunnerStopReason.INTERRUPTED -> GoalAttemptLedgerAction.INTERRUPTION
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> GoalAttemptLedgerAction.RETRY
    GoalRunnerStopReason.FAILED,
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME
  }

fun GoalRunnerStopReason.toDiagnosticClass(): String =
  when (this) {
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME -> "no_terminal_workflow_state"
    GoalRunnerStopReason.FAILED -> "malformed_result_json"
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
    GoalRunnerStopReason.BLOCKED,
    -> "child_process_failed"
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> name.lowercase()
  }

fun GoalRunnerStopReason.nextSafeAction(): String =
  when (this) {
    GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
    GoalRunnerStopReason.TIMEOUT,
    GoalRunnerStopReason.INTERRUPTED,
    GoalRunnerStopReason.RECONCILED_RESUMABLE,
    GoalRunnerStopReason.AWAITING_OPERATOR_DECISION,
    GoalRunnerStopReason.PAUSED,
    -> "resume_from_last_resumable_step"
    GoalRunnerStopReason.FAILED -> "inspect_child_output_then_resume"
    GoalRunnerStopReason.BLOCKED,
    GoalRunnerStopReason.POLICY_BLOCKED,
    GoalRunnerStopReason.PULL_REQUEST_FAILED,
    GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
    -> "inspect_blocked_reason"
  }
