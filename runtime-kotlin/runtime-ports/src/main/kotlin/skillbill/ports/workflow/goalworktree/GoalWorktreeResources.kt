package skillbill.ports.workflow.goalworktree

import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import java.nio.file.Path

interface GoalWorktreeResources {
  fun plannedPath(
    repoRoot: Path,
    issueKey: String,
    reservationId: String? = null,
  ): Path

  fun gitWorktreeIdentity(path: Path): String?

  /**
   * Creates the goal's detached worktree at [revision]. A recorded path requires matching durable [ownership]; an
   * existing registered checkout is adopted only after Git finishes creating it and confirms the same repository.
   * A missing initialized checkout requires matching safe-removal evidence and the original commit before
   * reconstruction. Consumed evidence cannot authorize another reconstruction. Missing evidence retains the
   * ownership record and returns a failure with recovery guidance.
   * May wait for a concurrent launch to finish. Never removes or overwrites an existing directory.
   */
  fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
    ownership: GoalPlanningOwnership? = null,
  ): GoalWorktreeResult

  /** Returns the worktree that has [branch] checked out, or null when none does or the list cannot be read. */
  fun checkoutHolding(
    repoRoot: Path,
    branch: String,
  ): Path?

  /**
   * Removes a clean, published worktree with matching transferred goal or initialized standalone ownership.
   * Standalone callers fence settlement against worker admission and transfer, and retain advanced revisions.
   */
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
