package skillbill.ports.diff

import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.ports.diff.model.ReviewCommitMetadata
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.diff.model.ReviewIndexEntry
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import java.nio.file.Path

abstract class DiffResolverPortDefaults : DiffResolverPort {
  open override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String? = null

  open override fun mergeBase(
    repoRoot: Path,
    revision: String,
  ): String? = null

  open override fun pullRequestBaseCommit(repoRoot: Path): String? = null

  open override fun currentBranchName(repoRoot: Path): String? = null

  open override fun firstParentCommits(
    repoRoot: Path,
    base: String,
    head: String,
  ): List<String>? = null

  open override fun commitMetadata(
    repoRoot: Path,
    sha: String,
  ): ReviewCommitMetadata? = null

  open override fun indexEntries(repoRoot: Path): List<ReviewIndexEntry>? = null

  open override fun untrackedPaths(repoRoot: Path): List<String>? = null

  open override fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String? = null

  open override fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ): Map<String, ReviewCheckpointFileIdentity> =
    throw InvalidReviewContextSchemaError(
      "review-source",
      "This diff resolver cannot capture worktree evidence identities.",
    )

  open override fun readDiff(
    path: Path,
    maxBytes: Long,
  ): String? = null
}
