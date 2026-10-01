package skillbill.ports.diff

import skillbill.ports.diff.model.ReviewCommitMetadata
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.diff.model.ReviewIndexEntry
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import java.nio.file.Path

/**
 * Revision and commit facts about a repository under review.
 *
 * Result contract for every query that returns a nullable value: `null` means the fact was
 * unavailable and the adapter has already recorded why; an empty string or empty list is a
 * successful empty result. [InterruptedException] is never converted into a result and
 * propagates to the caller.
 */
interface ReviewRevisionFactsPort {
  /** Resolves [revision] to the commit it names, or null when it names none. */
  fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String?

  /** Returns the merge base of `HEAD` and [revision], or null when they share none. */
  fun mergeBase(
    repoRoot: Path,
    revision: String,
  ): String?

  /** Returns the base commit of the current pull request, or null when none is known. */
  fun pullRequestBaseCommit(repoRoot: Path): String?

  /** Returns the current branch name, or null when it cannot be determined. */
  fun currentBranchName(repoRoot: Path): String?

  /** Lists the first-parent commits in `base..head`, oldest first. */
  fun firstParentCommits(
    repoRoot: Path,
    base: String,
    head: String,
  ): List<String>?

  /** Returns the parents and subject of [sha]. */
  fun commitMetadata(
    repoRoot: Path,
    sha: String,
  ): ReviewCommitMetadata?
}

/** Review facts about a repository; follows the [ReviewRevisionFactsPort] result contract. */
interface DiffResolverPort : ReviewRevisionFactsPort {
  /** Lists every entry of the index. */
  fun indexEntries(repoRoot: Path): List<ReviewIndexEntry>?

  /** Lists untracked paths that are not ignored. */
  fun untrackedPaths(repoRoot: Path): List<String>?

  /** Returns the diff text answering [query]; an empty string is an empty diff. */
  fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String?

  fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ): Map<String, ReviewCheckpointFileIdentity>

  fun readDiff(
    path: Path,
    maxBytes: Long,
  ): String?
}
