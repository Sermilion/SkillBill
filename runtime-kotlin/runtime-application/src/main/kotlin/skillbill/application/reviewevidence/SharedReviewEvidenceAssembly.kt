package skillbill.application.reviewevidence

import skillbill.application.reviewevidence.model.DiffResolutionException
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import java.nio.file.Path

internal data class SharedReviewEvidenceCommits(
  val baseRevision: String,
  val headRevision: String,
  val commits: List<RawCommitDiff>,
  val syntheticSource: ReviewCommitSource?,
  val syntheticReason: String?,
) {
  init {
    require((syntheticSource == null) == (syntheticReason == null)) {
      "A synthetic shared review evidence record must name both its source and its reason."
    }
    require(syntheticSource != null || commits.isNotEmpty()) {
      "A non-synthetic shared review evidence record must carry at least one commit."
    }
  }
}

internal data class SharedReviewEvidenceRecord(
  val aggregateDiff: String,
  val sequence: SharedReviewEvidenceCommits,
  val storePath: String? = null,
)

internal class SharedReviewEvidenceAssembler(private val diffResolver: DiffResolverPort) {
  internal fun assemble(
    scope: ParallelReviewScope,
    repoRoot: Path,
    range: ReviewCommitRange,
    suppliedDiff: Boolean,
  ): SharedReviewEvidenceCommits {
    val declaredSynthetic =
      when {
        suppliedDiff -> ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF
        scope == ParallelReviewScope.STAGED ||
          scope == ParallelReviewScope.UNSTAGED ||
          scope == ParallelReviewScope.UNCOMMITTED ||
          scope == ParallelReviewScope.WORKTREE_FROM_BASE ->
          ReviewCommitSource.SYNTHETIC_WORKING_TREE
        else -> null
      }
    if (declaredSynthetic != null) {
      return synthetic(range, declaredSynthetic, "non-commit review scope")
    }
    val shas = revList(repoRoot, range)
    if (shas.isEmpty()) {
      return synthetic(
        range,
        ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
        "git enumerated no commits for ${range.span} in the local object store",
      )
    }
    val commits = shas.map { readCommit(repoRoot, it, range.baseRevision) }
    if (commits.first().parentSha != range.baseRevision) {
      return synthetic(
        range,
        ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
        "the first-parent sequence for ${range.span} starts at '${commits.first().parentSha}', " +
          "not the review base; commit attribution would omit merged-in history",
      )
    }
    return SharedReviewEvidenceCommits(
      baseRevision = range.baseRevision,
      headRevision = range.headRevision,
      commits = commits,
      syntheticSource = null,
      syntheticReason = null,
    )
  }

  private fun synthetic(
    range: ReviewCommitRange,
    source: ReviewCommitSource,
    reason: String,
  ) = SharedReviewEvidenceCommits(
    baseRevision = range.baseRevision,
    headRevision = range.headRevision,
    commits = emptyList(),
    syntheticSource = source,
    syntheticReason = reason,
  )

  private fun revList(
    repoRoot: Path,
    range: ReviewCommitRange,
  ): List<String> =
    diffResolver.firstParentCommits(repoRoot, range.baseRevision, range.headRevision)
      ?: throw DiffResolutionException("Could not enumerate the commit sequence for ${range.span}.")

  private fun readCommit(
    repoRoot: Path,
    sha: String,
    baseRevision: String,
  ): RawCommitDiff {
    val metadata =
      diffResolver.commitMetadata(repoRoot, sha)
        ?: throw DiffResolutionException("Could not read commit metadata for '$sha'.")
    val parent = metadata.parentShas.firstOrNull() ?: baseRevision
    val diff =
      diffResolver.diff(repoRoot, ReviewDiffQuery.CommitRange(parent, sha))
        ?: throw DiffResolutionException("Could not read the incremental diff for commit '$sha'.")
    return RawCommitDiff(sha, parent, metadata.subject, diff)
  }
}

internal object SharedReviewEvidenceProjection {
  internal fun project(
    record: SharedReviewEvidenceCommits,
    aggregate: ReviewDiffEvidence,
  ): ResolvedCommitSequence {
    val range = ReviewCommitRange(record.baseRevision, record.headRevision)
    record.syntheticSource?.let { source ->
      return ResolvedCommitSequence(
        listOf(ReviewCommitUnit.synthetic(source, aggregate.hunks)),
        ReviewCommitCoverageFact(
          baseRevision = range.baseRevision,
          headRevision = range.headRevision,
          commitCount = 1,
          chainVerified = false,
          pathCoverageVerified = true,
          degradedReason = "single synthetic unit from ${source.name.lowercase()}: ${record.syntheticReason}",
        ),
      )
    }
    val units = parseCommitUnits(record.commits)
    verifyCoverage(units, aggregate, range)
    return ResolvedCommitSequence(
      units,
      ReviewCommitCoverageFact(
        range.baseRevision,
        range.headRevision,
        units.size,
        chainVerified = true,
        pathCoverageVerified = true,
      ),
    )
  }

  private fun verifyCoverage(
    units: List<ReviewCommitUnit>,
    aggregate: ReviewDiffEvidence,
    range: ReviewCommitRange,
  ) {
    coverageViolation(units, aggregate, range)?.let { throw DiffResolutionException(it) }
  }

  private fun coverageViolation(
    units: List<ReviewCommitUnit>,
    aggregate: ReviewDiffEvidence,
    range: ReviewCommitRange,
  ): String? {
    val shas = units.map { it.commitSha }
    val brokenLink = units.zipWithNext().firstOrNull { (previous, next) -> next.parentSha != previous.commitSha }
    val hunkIds = units.flatMap { it.hunkIds }
    val uncovered =
      aggregate.hunks.map { it.path }.toSet() -
        units.flatMap { unit -> unit.hunks.map { it.path } }.toSet()
    return when {
      units.last().commitSha != range.headRevision ->
        "Resolved commit sequence does not span ${range.span}; the review delta would be incomplete."
      shas.distinct().size != shas.size -> "Resolved commit sequence lists the same commit more than once."
      brokenLink != null ->
        "Resolved commit sequence is broken between '${brokenLink.first.commitSha}' and " +
          "'${brokenLink.second.commitSha}'."
      hunkIds.distinct().size != hunkIds.size ->
        "Resolved commit sequence attributes the same hunk to more than one commit."
      uncovered.isNotEmpty() ->
        "Resolved commit sequence omits paths the base-to-head delta changes: ${uncovered.sorted()}."
      else -> null
    }
  }
}

internal fun parseCommitUnits(commits: List<RawCommitDiff>): List<ReviewCommitUnit> =
  commits.mapIndexed { index, commit ->
    ReviewCommitUnit.ofCommit(
      commitSha = commit.commitSha,
      parentSha = commit.parentSha,
      subject = commit.subject,
      orderIndex = index,
      hunks = if (commit.diff.isBlank()) emptyList() else ReviewDiffEvidence.parse(commit.diff).hunks,
    )
  }
