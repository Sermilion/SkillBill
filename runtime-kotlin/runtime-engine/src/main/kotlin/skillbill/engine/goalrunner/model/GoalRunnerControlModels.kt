package skillbill.engine.goalrunner.model

enum class GoalRunnerPauseStatus(val wireValue: String) {
  NOT_FOUND("not_found"),
  PAUSED("paused"),
  REQUESTED("requested"),
}

data class GoalRunnerPauseResult(
  val issueKey: String,
  val parentWorkflowId: String? = null,
  val status: GoalRunnerPauseStatus,
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

enum class GoalRunnerStopStatus(val wireValue: String) {
  STOPPED("stopped"),
  ALREADY_STOPPED("already_stopped"),
  NO_LIVE_LEASE("no_live_lease"),
  IDENTITY_MISMATCH("identity_mismatch"),
  NOT_FOUND("not_found"),
}

data class GoalRunnerStopVerbResult(
  val issueKey: String,
  val status: GoalRunnerStopStatus,
  val parentWorkflowId: String? = null,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val terminationAttempted: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

enum class GoalRunnerResumeStatus(val wireValue: String) {
  NOT_FOUND("not_found"),
  NOT_PAUSED("not_paused"),
  RESUMED("resumed"),
}

data class GoalRunnerResumeResult(
  val issueKey: String,
  val parentWorkflowId: String? = null,
  val status: GoalRunnerResumeStatus,
  val clearedPauseReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}
