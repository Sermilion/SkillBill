package skillbill.engine.goalrunner.model

import java.nio.file.Path

public data class GoalRunnerReplanRequest(
  public val issueKey: String,
  public val subtaskId: Int,
  public val repoRoot: Path? = null,
  public val includeSharedPreplan: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
  }
}

public data class GoalRunnerReplanResult(
  public val issueKey: String,
  public val parentWorkflowId: String,
  public val subtaskId: Int,
  public val discardedPlan: Boolean,
  public val discardedSharedPreplan: Boolean = false,
  public val cascadedPlanSubtaskIds: List<Int> = emptyList(),
  public val clearedChildSubtaskIds: List<Int> = emptyList(),
  public val before: GoalRunnerReplanSnapshot,
  public val after: GoalRunnerReplanSnapshot,
)

public data class GoalRunnerReplanSnapshot(
  public val status: String,
  public val currentSubtaskId: Int?,
  public val currentAction: String,
  public val sharedPreplanPrepared: Boolean,
  public val plannedSubtaskIds: List<Int>,
  public val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)
