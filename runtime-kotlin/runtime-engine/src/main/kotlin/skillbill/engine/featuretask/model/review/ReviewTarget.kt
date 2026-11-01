package skillbill.engine.featuretask.model.review

import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.review.context.model.hunk.ReviewBaselineUntrackedPolicy
import java.nio.file.Path

/**
 * What one review call inspects. Each target composes the opening lines of the review prompt; last commit reads its
 * revisions from the prepared review input.
 */
public sealed interface ReviewTarget {
  /** The opening prompt lines for this target, given the prepared input's base and head revisions. */
  public fun openingLines(
    baseRevision: String,
    headRevision: String,
  ): List<String>

  public data object LastCommit : ReviewTarget {
    override fun openingLines(
      baseRevision: String,
      headRevision: String,
    ): List<String> =
      listOf(
        "Review the last commit `$headRevision` against its first parent `$baseRevision`.",
        "Inspect with `git diff $baseRevision $headRevision` in this repository workspace.",
      )
  }

  public data object Uncommitted : ReviewTarget {
    override fun openingLines(
      baseRevision: String,
      headRevision: String,
    ): List<String> =
      listOf(
        "Review the uncommitted changes in this repository workspace against `HEAD`, including untracked files.",
        "Inspect with `git diff HEAD` plus `git status --porcelain` for untracked files.",
      )
  }

  public data class Commit(val sha: String) : ReviewTarget {
    override fun openingLines(
      baseRevision: String,
      headRevision: String,
    ): List<String> =
      listOf(
        "Review commit `$sha` against its first parent `$sha^`.",
        "Inspect with `git diff $sha^ $sha` in this repository workspace.",
      )
  }

  public data class Scoped(
    public val scope: ParallelReviewScope,
    public val baseRevision: String? = null,
    public val headRevision: String? = null,
    public val suppliedDiffPath: Path? = null,
  ) : ReviewTarget {
    override fun openingLines(
      baseRevision: String,
      headRevision: String,
    ): List<String> =
      suppliedDiffPath?.let { path ->
        listOf(
          "Review exactly the diff in `$path`, taken between `${this.baseRevision}` and `${this.headRevision}`.",
          "Read the diff file; do not recompute it from git.",
        )
      } ?: scopeLines()

    private fun scopeLines(): List<String> =
      when (scope) {
        ParallelReviewScope.STAGED ->
          listOf("Review the staged changes in this repository workspace.", "Inspect with `git diff --cached`.")
        ParallelReviewScope.UNSTAGED ->
          listOf("Review the unstaged changes in this repository workspace.", "Inspect with `git diff`.")
        ParallelReviewScope.UNCOMMITTED -> Uncommitted.openingLines("", "")
        ParallelReviewScope.PR ->
          listOf(
            "Review the pull request of the current branch against its base branch.",
            "Inspect with `gh pr diff` in this repository workspace.",
          )
        ParallelReviewScope.BRANCH, ParallelReviewScope.WORKTREE_FROM_BASE ->
          revisionLines() ?: listOf(
            "Review the current branch against the default branch.",
            "Inspect with `git diff origin/HEAD...HEAD` in this repository workspace.",
          )
      }

    private fun revisionLines(): List<String>? {
      val base = baseRevision ?: return null
      val head = headRevision ?: return null
      return listOf(
        "Review the changes from `$base` to `$head`.",
        "Inspect with `git diff $base $head` in this repository workspace.",
      )
    }
  }
}

public data class ReviewInvocation(
  public val target: ReviewTarget? = null,
  public val reviewRunId: String? = null,
  public val reviewSessionId: String? = null,
  public val prelaunchExpansions: List<ReviewPrelaunchExpansion> = emptyList(),
  public val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
)
