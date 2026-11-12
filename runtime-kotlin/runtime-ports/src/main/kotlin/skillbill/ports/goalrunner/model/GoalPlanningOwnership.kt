package skillbill.ports.goalrunner.model

import java.nio.file.Path

data class GoalPlanningOwnership(
  val repositoryIdentity: String,
  val normalizedIssueKey: String,
  val workflowId: String,
  val reservationId: String,
  val ownedPath: Path,
  val initialCommit: String,
  val state: State,
  val parentWorkflowId: String? = null,
  val gitWorktreeIdentity: String? = null,
) {
  enum class State(val wireValue: String) {
    RESERVED("reserved"),
    INITIALIZED("initialized"),
    TRANSFERRED("transferred"),
    SUPERSEDED("superseded"),
  }
}

sealed interface GoalPlanningOwnershipWriteResult {
  data class Stored(val ownership: GoalPlanningOwnership) : GoalPlanningOwnershipWriteResult

  data class Conflict(val ownership: GoalPlanningOwnership?) : GoalPlanningOwnershipWriteResult

  data class Missing(val path: Path) : GoalPlanningOwnershipWriteResult
}
