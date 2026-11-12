package skillbill.ports.workflow.branchworktree.model

import java.nio.file.Path

enum class BranchWorktreeHolderKind(val wireValue: String) {
  PHASE("phase"),
  OPERATION("operation"),
  APP_RUN("app-run"),
}

data class BranchWorktreeHolder(val kind: BranchWorktreeHolderKind, val id: String)

sealed interface BranchWorktreeObservation {
  data class RuntimeManaged(
    val checkoutRoot: Path,
    val primaryRepositoryRoot: Path,
    val branch: String,
    val head: String,
    val holderPresent: Boolean,
  ) : BranchWorktreeObservation

  data class CallerOwned(
    val checkoutRoot: Path,
    val primaryRepositoryRoot: Path,
    val branch: String,
    val head: String,
  ) : BranchWorktreeObservation

  data class Unavailable(val reason: String) : BranchWorktreeObservation
}

sealed interface BranchWorktreeCreation {
  data class Created(val path: Path) : BranchWorktreeCreation

  data object Missing : BranchWorktreeCreation

  data class Failed(val reason: String) : BranchWorktreeCreation
}

sealed interface BranchWorktreeRelease {
  data object NotHeld : BranchWorktreeRelease

  data object StillHeld : BranchWorktreeRelease

  data class Removed(val path: Path) : BranchWorktreeRelease

  data class Kept(val path: Path, val detail: String) : BranchWorktreeRelease
}
