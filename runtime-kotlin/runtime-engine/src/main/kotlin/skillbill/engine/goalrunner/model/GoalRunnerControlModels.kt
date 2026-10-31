package skillbill.engine.goalrunner.model

public enum class GoalRunnerPauseStatus(public val wireValue: String) {
  NOT_FOUND("not_found"),
  CHANGED_TARGET("changed_target"),
  PAUSED("paused"),
  REQUESTED("requested"),
}

public data class GoalRunnerPauseResult(
  public val issueKey: String,
  public val parentWorkflowId: String? = null,
  public val status: GoalRunnerPauseStatus,
  public val paused: Boolean = false,
  public val pauseRequested: Boolean = false,
  public val pauseReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

public enum class GoalRunnerStopStatus(public val wireValue: String) {
  STOPPED("stopped"),
  ALREADY_STOPPED("already_stopped"),
  NO_LIVE_LEASE("no_live_lease"),
  IDENTITY_MISMATCH("identity_mismatch"),
  FOREIGN_HOST("foreign_host"),
  PID_REUSED("pid_reused"),
  UNINSPECTABLE("uninspectable"),
  CHANGED_TARGET("changed_target"),
  TERMINATION_INCOMPLETE("termination_incomplete"),
  NOT_FOUND("not_found"),
}

public data class GoalRunnerStopVerbResult(
  public val issueKey: String,
  public val status: GoalRunnerStopStatus,
  public val parentWorkflowId: String? = null,
  public val pauseReason: String? = null,
  public val pausedAt: String? = null,
  public val terminationAttempted: Boolean = false,
  internal val terminationFailureCode: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

public enum class GoalRunnerResumeStatus(public val wireValue: String) {
  NOT_FOUND("not_found"),
  NOT_PAUSED("not_paused"),
  RESUMED("resumed"),
  OPERATOR_REQUIRED("operator_required"),
}

public data class GoalRunnerResumeResult(
  public val issueKey: String,
  public val parentWorkflowId: String? = null,
  public val status: GoalRunnerResumeStatus,
  public val clearedPauseReason: String? = null,
  public val appliedRepairs: List<GoalRunnerAppliedRepair> = emptyList(),
  public val refusalReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}
