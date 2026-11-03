package skillbill.ports.workflow.goalworktree

import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import java.nio.file.Path

object InPlaceGoalWorktreeResources : GoalWorktreeResources {
  override fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
  ): GoalWorktreeResult = GoalWorktreeResult.Adopted(repoRoot)

  override fun checkoutHolding(
    repoRoot: Path,
    branch: String,
  ): Path? = null
}
