package skillbill.ports.workflow.goalworktree

import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
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
}
