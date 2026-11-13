package skillbill.ports.workflow.goalworktree.model

import java.nio.file.Path

sealed interface GoalWorktreeResult {
  data class Created(val path: Path, val gitWorktreeIdentity: String? = null) : GoalWorktreeResult

  data class Adopted(val path: Path, val gitWorktreeIdentity: String? = null) : GoalWorktreeResult

  data class Failed(val reason: String) : GoalWorktreeResult
}
