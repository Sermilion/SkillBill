package skillbill.ports.workflow.goalworktree

import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import java.nio.file.Path

interface GoalWorktreeResources {
  /**
   * Creates the goal's detached worktree at [revision], or adopts the one git already lists at [recordedPath] or at the
   * goal's state path. Adopts only once git has finished creating the worktree, and may wait for a concurrent launch to
   * finish. Never removes or overwrites an existing directory.
   */
  fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
  ): GoalWorktreeResult

  /** Returns the worktree that has [branch] checked out, or null when none does or the list cannot be read. */
  fun checkoutHolding(
    repoRoot: Path,
    branch: String,
  ): Path?

  /** Removes an owned clean worktree or returns the reason it must stay. */
  fun settle(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement

  /** Reads the retention decision without changing the worktree or repository. */
  fun retentionVerdict(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement.Retained?

  /**
   * Switches the primary checkout at [primaryRoot] to the local [branch] only when it has no tracked changes and no git
   * operation in progress. Never forces, stashes, resets or discards anything, and reports expected outcomes as
   * results.
   */
  fun checkoutGoalBranch(
    primaryRoot: Path,
    branch: String,
  ): GoalBranchCheckout
}
