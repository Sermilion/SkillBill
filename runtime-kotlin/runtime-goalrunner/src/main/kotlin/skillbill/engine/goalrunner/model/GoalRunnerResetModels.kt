package skillbill.engine.goalrunner.model

import java.nio.file.Path

public data class GoalRunnerResetRequest(
  public val issueKey: String,
  public val hard: Boolean,
  public val preservePlanning: Boolean = false,
  public val subtaskId: Int? = null,
  public val deleteChildWorkflow: Boolean = false,
  public val repoRoot: Path? = null,
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

public data class GoalRunnerResetResult(
  public val issueKey: String,
  public val mode: String,
  public val parentWorkflowId: String,
  public val before: GoalRunnerResetSnapshot,
  public val after: GoalRunnerResetSnapshot,
  public val recovery: GoalRunnerChildRecoveryDiagnostic? = null,
  public val branchActionTaken: String? = null,
  public val refusalReason: String? = null,
  public val remedyCommand: String? = null,
)

public data class GoalRunnerChildRecoveryDiagnostic(
  public val subtaskId: Int,
  public val workflowId: String,
  public val classification: String,
  public val recoveryCommand: String?,
)

public data class GoalRunnerResetSnapshot(
  public val status: String,
  public val currentSubtaskId: Int?,
  public val currentAction: String,
  public val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)

public data class GoalRunnerResetSubtaskSnapshot(
  public val id: Int,
  public val status: String,
  public val branch: String?,
  public val workflowId: String?,
  public val commitSha: String?,
  public val blockedReason: String?,
  public val lastResumableStep: String?,
)
