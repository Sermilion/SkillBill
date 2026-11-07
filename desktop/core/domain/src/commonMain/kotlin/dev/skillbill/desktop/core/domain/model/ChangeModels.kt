package dev.skillbill.desktop.core.domain.model

/** Where an execution's changes are measured from; every baseline names the worktree git runs in. */
sealed interface ChangeBaseline {
  val worktreeRoot: String

  data class SubtaskStart(override val worktreeRoot: String, val baseSha: String, val excludedUntrackedPaths: List<String>) : ChangeBaseline

  data class RunStart(override val worktreeRoot: String, val baseSha: String, val excludedUntrackedPaths: List<String>) : ChangeBaseline

  data class TargetMergeBase(override val worktreeRoot: String, val targetBranch: String?) : ChangeBaseline

  data class CommittedBranchRange(
    override val worktreeRoot: String,
    val targetBranch: String,
    val headBranch: String,
  ) : ChangeBaseline {
    val headRef: String get() = "refs/heads/$headBranch"
  }

  data class CommittedRevisionRange(
    override val worktreeRoot: String,
    val fromSha: String,
    val toSha: String,
  ) : ChangeBaseline
}

enum class ChangeStatus { Added, Modified, Deleted, Renamed, Untracked }

data class ChangedFile(val path: String, val oldPath: String?, val status: ChangeStatus, val insertions: Int?, val deletions: Int?) {
  /** Git reports no line counts for a binary file. */
  val binary: Boolean get() = insertions == null && deletions == null
}

data class ChangeSet(val baseSha: String, val files: List<ChangedFile>, val insertions: Int, val deletions: Int)

sealed interface ChangesState {
  data object Loading : ChangesState

  data class Listed(val changes: ChangeSet) : ChangesState

  data object NoSubtask : ChangesState

  data class BaseUnavailable(val reason: String) : ChangesState

  data class NotARepository(val worktreeRoot: String) : ChangesState

  data class Unavailable(val problem: ReadProblem) : ChangesState
}

enum class DiffLineKind { Context, Added, Removed, NoNewlineMarker }

/** One diff body line; context lines carry both numbers, added lines only the new one, removed lines only the old one. */
data class DiffLine(val kind: DiffLineKind, val oldNumber: Int?, val newNumber: Int?, val text: String)

data class DiffHunk(val header: String, val lines: List<DiffLine>, val source: String? = null)

data class FileDiff(val hunks: List<DiffHunk>, val insertions: Int, val deletions: Int)

sealed interface FileDiffState {
  data class Shown(val diff: FileDiff) : FileDiffState

  data object Binary : FileDiffState

  data class TooLarge(val limitBytes: Int) : FileDiffState

  data class BaseUnavailable(val reason: String) : FileDiffState

  data class Unavailable(val problem: ReadProblem) : FileDiffState
}
