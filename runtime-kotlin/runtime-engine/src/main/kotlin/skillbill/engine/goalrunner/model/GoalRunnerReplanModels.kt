package skillbill.engine.goalrunner.model

import java.nio.file.Path

data class GoalRunnerReplanRequest(
  val issueKey: String,
  val subtaskId: Int,
  val repoRoot: Path? = null,
  val includeSharedPreplan: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
  }
}

data class GoalRunnerReplanResult(
  val issueKey: String,
  val parentWorkflowId: String,
  val subtaskId: Int,
  val discardedPlan: Boolean,
  val discardedSharedPreplan: Boolean = false,
  val cascadedPlanSubtaskIds: List<Int> = emptyList(),
  val clearedChildSubtaskIds: List<Int> = emptyList(),
  val before: GoalRunnerReplanSnapshot,
  val after: GoalRunnerReplanSnapshot,
)

data class GoalRunnerReplanSnapshot(
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val sharedPreplanPrepared: Boolean,
  val plannedSubtaskIds: List<Int>,
  val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)
