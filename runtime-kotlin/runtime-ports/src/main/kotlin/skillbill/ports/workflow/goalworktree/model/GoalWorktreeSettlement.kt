package skillbill.ports.workflow.goalworktree.model

import skillbill.ports.monitor.model.MonitorRetentionReason
import java.nio.file.Path

data class GoalWorktreeSettleRequest(val primaryRoot: Path, val worktreePath: Path)

sealed interface GoalWorktreeSettlement {
  data object Removed : GoalWorktreeSettlement

  data object Absent : GoalWorktreeSettlement

  data class Retained(
    val reason: MonitorRetentionReason,
    val path: Path,
    val detail: String,
  ) : GoalWorktreeSettlement
}
