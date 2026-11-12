package skillbill.ports.workflow.goalworktree

import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import java.nio.file.Path

object InPlaceGoalWorktreeResources : GoalWorktreeResources {
  override fun plannedPath(
    repoRoot: Path,
    issueKey: String,
    reservationId: String?,
  ): Path = repoRoot

  override fun gitWorktreeIdentity(path: Path): String? = null

  override fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
    ownership: GoalPlanningOwnership?,
  ): GoalWorktreeResult = GoalWorktreeResult.Adopted(repoRoot)

  override fun checkoutHolding(
    repoRoot: Path,
    branch: String,
  ): Path? = null

  override fun settle(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement = GoalWorktreeSettlement.Absent

  override fun retentionVerdict(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement.Retained? = null

  override fun checkoutGoalBranch(
    primaryRoot: Path,
    branch: String,
  ): GoalBranchCheckout = GoalBranchCheckout.CheckedOut
}
