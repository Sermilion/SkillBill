package skillbill.ports.diff.model

/** A purpose-built diff query answered by a diff resolver. */
sealed interface ReviewDiffQuery {
  /** Changes staged in the index. */
  data object Staged : ReviewDiffQuery

  /** Changes in the worktree that are not staged. */
  data object Unstaged : ReviewDiffQuery

  /** Changes between two commits. */
  data class CommitRange(
    val base: String,
    val head: String,
  ) : ReviewDiffQuery

  /** Changes of a pull request between its base and head commits. */
  data class PullRequest(
    val base: String,
    val head: String,
  ) : ReviewDiffQuery

  /** Tracked changes of the worktree against [base], optionally limited to [pathspec]. */
  data class WorkingTree(
    val base: String,
    val pathspec: List<String>,
    val includeBinary: Boolean,
  ) : ReviewDiffQuery

  /** The full-file patch of one untracked [path]. */
  data class UntrackedFile(
    val path: String,
  ) : ReviewDiffQuery
}

data class ReviewCommitMetadata(
  val parentShas: List<String>,
  val subject: String,
)

data class ReviewIndexEntry(
  val path: String,
  val mode: String,
  val objectId: String,
  val stage: Int,
)
