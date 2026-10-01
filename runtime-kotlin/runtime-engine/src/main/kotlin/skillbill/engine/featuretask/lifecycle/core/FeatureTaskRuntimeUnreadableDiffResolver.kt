package skillbill.engine.featuretask.lifecycle.core

import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.model.ReviewCommitMetadata
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.diff.model.ReviewIndexEntry
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import java.nio.file.Path

object FeatureTaskRuntimeUnreadableDiffResolver : DiffResolverPort {
  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String? = null

  override fun mergeBase(
    repoRoot: Path,
    revision: String,
  ): String? = null

  override fun pullRequestBaseCommit(repoRoot: Path): String? = null

  override fun currentBranchName(repoRoot: Path): String? = null

  override fun firstParentCommits(
    repoRoot: Path,
    base: String,
    head: String,
  ): List<String>? = null

  override fun commitMetadata(
    repoRoot: Path,
    sha: String,
  ): ReviewCommitMetadata? = null

  override fun indexEntries(repoRoot: Path): List<ReviewIndexEntry>? = null

  override fun untrackedPaths(repoRoot: Path): List<String>? = null

  override fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String? = null

  override fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ): Map<String, ReviewCheckpointFileIdentity> =
    throw InvalidReviewContextSchemaError(
      "review-source",
      "This diff resolver cannot capture worktree evidence identities.",
    )

  override fun readDiff(
    path: Path,
    maxBytes: Long,
  ): String? = null
}
