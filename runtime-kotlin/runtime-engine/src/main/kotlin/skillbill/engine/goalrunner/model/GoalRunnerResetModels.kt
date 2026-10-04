package skillbill.engine.goalrunner.model

import java.nio.file.Path

data class GoalRunnerResetRequest(
  val issueKey: String,
  val hard: Boolean,
  val preservePlanning: Boolean = false,
  val subtaskId: Int? = null,
  val deleteChildWorkflow: Boolean = false,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(!preservePlanning || hard) { "preservePlanning requires a hard reset." }
    require((subtaskId != null) == deleteChildWorkflow) {
      "subtaskId and deleteChildWorkflow must be supplied together."
    }
    require(subtaskId == null || subtaskId > 0) { "subtaskId must be positive." }
    require(!deleteChildWorkflow || !hard) { "Scoped child deletion is incompatible with a hard reset." }
    require(!deleteChildWorkflow || !preservePlanning) {
      "Scoped child deletion preserves planning intrinsically and cannot use preservePlanning."
    }
  }
}

data class GoalRunnerResetResult(
  val issueKey: String,
  val mode: String,
  val parentWorkflowId: String,
  val before: GoalRunnerResetSnapshot,
  val after: GoalRunnerResetSnapshot,
  val recovery: GoalRunnerChildRecoveryDiagnostic? = null,
  val branchActionTaken: String? = null,
  val refusalReason: String? = null,
  val remedyCommand: String? = null,
)

data class GoalRunnerChildRecoveryDiagnostic(
  val subtaskId: Int,
  val workflowId: String,
  val classification: String,
  val recoveryCommand: String?,
)

data class GoalRunnerResetSnapshot(
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)

data class GoalRunnerResetSubtaskSnapshot(
  val id: Int,
  val status: String,
  val branch: String?,
  val workflowId: String?,
  val commitSha: String?,
  val blockedReason: String?,
  val lastResumableStep: String?,
)
