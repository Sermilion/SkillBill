package skillbill.engine.goalrunner.model

import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import java.nio.file.Path

public data class GoalRunnerOperatorDecisionRequest(
  public val issueKey: String,
  public val subtaskId: Int,
  public val decision: GoalSubtaskOperatorDecision,
  public val repoRoot: Path? = null,
  public val instructions: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
  }
}

public sealed interface GoalRunnerOperatorDecisionResult {
  public data class Recorded(
    public val issueKey: String,
    public val parentWorkflowId: String,
    public val subtaskId: Int,
    public val workflowId: String,
    public val decision: String,
  ) : GoalRunnerOperatorDecisionResult

  public data class Rejected(public val issueKey: String, public val reason: String) : GoalRunnerOperatorDecisionResult
}

public data class GoalRunnerFreshQualityRequest(
  public val issueKey: String,
  public val reason: String,
  public val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(reason.isNotBlank()) { "reason is required." }
  }
}

public sealed interface GoalRunnerFreshQualityResult {
  public data class Chosen(
    public val issueKey: String,
    public val parentWorkflowId: String,
    public val reason: String,
  ) : GoalRunnerFreshQualityResult

  public data class Rejected(public val issueKey: String, public val reason: String) : GoalRunnerFreshQualityResult
}
