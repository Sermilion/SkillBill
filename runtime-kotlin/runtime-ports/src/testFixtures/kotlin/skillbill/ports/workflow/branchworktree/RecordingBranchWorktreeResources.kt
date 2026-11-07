package skillbill.ports.workflow.branchworktree

import skillbill.ports.workflow.branchworktree.model.BranchWorktreeCreation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeObservation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import java.nio.file.Path

class RecordingBranchWorktreeResources(
  var creation: BranchWorktreeCreation = BranchWorktreeCreation.Missing,
  var holdResult: Boolean = false,
  var releaseResult: BranchWorktreeRelease = BranchWorktreeRelease.NotHeld,
  var observation: BranchWorktreeObservation = BranchWorktreeObservation.Unavailable("No observation configured."),
) : BranchWorktreeResources {
  val created: MutableList<Pair<Path, String>> = mutableListOf()
  val held: MutableList<Pair<Path, BranchWorktreeHolder>> = mutableListOf()
  val released: MutableList<Pair<Path, BranchWorktreeHolder>> = mutableListOf()

  override fun create(
    repoRoot: Path,
    branch: String,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeCreation {
    created += repoRoot to branch
    return creation
  }

  override fun hold(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): Boolean {
    held += checkoutRoot to holder
    return holdResult
  }

  override fun release(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeRelease {
    released += checkoutRoot to holder
    return releaseResult
  }

  override fun observe(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeObservation = observation
}
