package skillbill.ports.workflow.branchworktree

import skillbill.ports.workflow.branchworktree.model.BranchWorktreeCreation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeObservation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import java.nio.file.Path

interface BranchWorktreeResources {
  /**
   * Adds a worktree with [branch] checked out beside the main checkout of [repoRoot] and records it as created by
   * skill-bill and held by [holder]. Checks out the local branch, or creates a local branch tracking the origin
   * branch of that name after fetching it. Returns [BranchWorktreeCreation.Missing] when neither exists.
   */
  fun create(
    repoRoot: Path,
    branch: String,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeCreation

  /**
   * Adds a worktree with a detached HEAD at the tip of [branch] beside [repoRoot]. This works when another checkout
   * already has the branch checked out. Returns [BranchWorktreeCreation.Missing] when neither local nor origin has it.
   */
  fun createDetached(
    repoRoot: Path,
    branch: String,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeCreation

  /**
   * Adds [holder] to the worktree at [checkoutRoot] when skill-bill created it. Returns true only when [holder] was
   * added by this call; a worktree skill-bill did not create is never recorded.
   */
  fun hold(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): Boolean

  fun observe(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeObservation

  /**
   * Drops [holder] from the worktree at [checkoutRoot] when skill-bill created it, then removes the worktree once no
   * holder remains. Removal never forces, never deletes the branch, and keeps a worktree with local changes.
   */
  fun release(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeRelease
}
