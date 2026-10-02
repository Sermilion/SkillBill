package skillbill.application.reviewevidence.model

enum class ParallelReviewScope {
  STAGED,
  UNSTAGED,
  UNCOMMITTED,
  BRANCH,
  PR,
  WORKTREE_FROM_BASE,
}

/** A diff or revision lookup that either produced its value or names why the evidence could not be read. */
sealed interface DiffResolution<out T> {
  data class Resolved<T>(val value: T) : DiffResolution<T>

  data class Unresolved(val message: String) : DiffResolution<Nothing>
}
