package skillbill.application.review.snapshot

import skillbill.application.reviewevidence.ResolvedCommitSequence
import skillbill.application.reviewevidence.ReviewCommitRange
import skillbill.application.reviewevidence.SharedReviewEvidenceAssembler
import skillbill.application.reviewevidence.SharedReviewEvidenceProjection
import skillbill.application.reviewevidence.model.DiffResolution
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.DiffResolverPortDefaults
import skillbill.ports.diff.model.ReviewCommitMetadata
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.review.context.model.commit.ReviewCommitSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReviewCommitSequenceResolverTest {
  private val repoRoot: Path = Path.of(".")

  private fun diffFor(
    path: String,
    line: String,
  ) = """
    diff --git a/$path b/$path
    --- a/$path
    +++ b/$path
    @@ -1,1 +1,2 @@
    +$line
    """.trimIndent()

  private class FakeGit(
    private val commits: Map<String, List<String>> = emptyMap(),
    private val metadata: Map<String, ReviewCommitMetadata> = emptyMap(),
    private val diffs: Map<ReviewDiffQuery, String> = emptyMap(),
  ) : DiffResolverPortDefaults() {
    val invoked: MutableList<String> = mutableListOf()

    override fun firstParentCommits(
      repoRoot: Path,
      base: String,
      head: String,
    ): List<String>? {
      invoked += "firstParentCommits $base..$head"
      return commits["$base..$head"]
    }

    override fun commitMetadata(
      repoRoot: Path,
      sha: String,
    ): ReviewCommitMetadata? {
      invoked += "commitMetadata $sha"
      return metadata[sha]
    }

    override fun diff(
      repoRoot: Path,
      query: ReviewDiffQuery,
    ): String? {
      invoked += "diff $query"
      return diffs[query]
    }

    override fun reviewWorktreeFileIdentities(
      root: Path,
      paths: List<String>,
    ) = emptyMap<String, ReviewCheckpointFileIdentity>()
  }

  private fun branchRepo(
    shas: List<String>,
    diffs: Map<String, String>,
    parents: Map<String, String>,
  ): FakeGit =
    FakeGit(
      commits = mapOf("base..head" to shas),
      metadata = shas.associateWith { sha -> ReviewCommitMetadata(listOf(parents.getValue(sha)), "subject $sha") },
      diffs = shas.associate { sha -> ReviewDiffQuery.CommitRange(parents.getValue(sha), sha) to diffs.getValue(sha) },
    )

  private fun sixCommitRepo(): FakeGit {
    val shas = (1..5).map { "c$it" } + "head"
    val parents = shas.mapIndexed { index, sha -> sha to if (index == 0) "base" else shas[index - 1] }.toMap()
    val diffs = shas.associateWith { diffFor("src/$it.kt", "line-$it") }
    return branchRepo(shas, diffs, parents)
  }

  private fun attempt(
    git: DiffResolverPort,
    scope: ParallelReviewScope,
    aggregateDiff: String,
    supplied: Boolean = false,
  ): DiffResolution<ResolvedCommitSequence> =
    when (
      val assembled =
        SharedReviewEvidenceAssembler(git).assemble(scope, repoRoot, ReviewCommitRange("base", "head"), supplied)
    ) {
      is DiffResolution.Unresolved -> assembled
      is DiffResolution.Resolved ->
        SharedReviewEvidenceProjection.project(assembled.value, ReviewDiffEvidence.parse(aggregateDiff))
    }

  private fun resolve(
    git: DiffResolverPort,
    scope: ParallelReviewScope,
    aggregateDiff: String,
    supplied: Boolean = false,
  ): ResolvedCommitSequence =
    assertIs<DiffResolution.Resolved<ResolvedCommitSequence>>(
      attempt(git, scope, aggregateDiff, supplied),
    ).value

  private fun unresolvedMessage(
    git: DiffResolverPort,
    aggregateDiff: String,
  ): String = assertIs<DiffResolution.Unresolved>(attempt(git, ParallelReviewScope.BRANCH, aggregateDiff)).message

  @Test fun `a six commit branch resolves an ordered first-parent sequence`() {
    val aggregate =
      (1..5).joinToString("\n") { diffFor("src/c$it.kt", "line-c$it") } +
        "\n" + diffFor("src/head.kt", "line-head")
    val resolved = resolve(sixCommitRepo(), ParallelReviewScope.BRANCH, aggregate)
    assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "head"), resolved.units.map { it.commitSha })
    assertEquals(listOf(0, 1, 2, 3, 4, 5), resolved.units.map { it.orderIndex })
    assertEquals("base", resolved.units.first().parentSha)
    assertTrue(resolved.coverageFact.chainVerified && resolved.coverageFact.pathCoverageVerified)
    assertEquals(6, resolved.coverageFact.commitCount)
    assertTrue(resolved.units.all { it.source == ReviewCommitSource.COMMIT_RANGE })
  }

  @Test fun `a merge commit is traversed by first parent only`() {
    val mergeDiff = diffFor("src/Merged.kt", "merged")
    val headDiff = diffFor("src/head.kt", "line-head")
    val git =
      FakeGit(
        commits = mapOf("base..head" to listOf("c1", "merge", "head")),
        metadata =
          mapOf(
            "c1" to ReviewCommitMetadata(listOf("base"), "subject c1"),
            "merge" to ReviewCommitMetadata(listOf("c1", "other"), "Merge branch 'other'"),
            "head" to ReviewCommitMetadata(listOf("merge"), "subject head"),
          ),
        diffs =
          mapOf(
            ReviewDiffQuery.CommitRange("base", "c1") to diffFor("src/c1.kt", "line-c1"),
            ReviewDiffQuery.CommitRange("c1", "merge") to mergeDiff,
            ReviewDiffQuery.CommitRange("merge", "head") to headDiff,
          ),
      )
    val resolved =
      resolve(
        git,
        ParallelReviewScope.BRANCH,
        diffFor("src/c1.kt", "line-c1") + "\n" + mergeDiff + "\n" + headDiff,
      )
    assertEquals(listOf("c1", "merge", "head"), resolved.units.map { it.commitSha })
    assertEquals(listOf("base", "c1", "merge"), resolved.units.map { it.parentSha })
    assertTrue(git.invoked.none { it.startsWith("diff") && "other" in it })
    assertTrue(resolved.coverageFact.chainVerified)
  }

  @Test fun `a base outside the first-parent chain degrades instead of aborting the review`() {
    val headDiff = diffFor("src/head.kt", "line-head")
    val git =
      branchRepo(
        listOf("head"),
        mapOf("head" to headDiff),
        mapOf("head" to "branchpoint"),
      )
    val resolved = resolve(git, ParallelReviewScope.BRANCH, headDiff)
    assertEquals(1, resolved.units.size)
    assertEquals(ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF, resolved.units.single().source)
    assertTrue("branchpoint" in resolved.coverageFact.degradedReason.orEmpty())
    assertEquals(false, resolved.coverageFact.chainVerified)
  }

  @Test fun `an empty commit stays in the sequence as a zero-hunk unit`() {
    val shas = listOf("c1", "head")
    val git =
      branchRepo(
        shas,
        mapOf("c1" to "", "head" to diffFor("src/A.kt", "alpha")),
        mapOf("c1" to "base", "head" to "c1"),
      )
    val resolved = resolve(git, ParallelReviewScope.BRANCH, diffFor("src/A.kt", "alpha"))
    assertEquals(2, resolved.units.size)
    assertEquals(emptyList(), resolved.units.first().hunks)
  }

  @Test fun `a sequence that omits a changed path fails loudly`() {
    val git =
      branchRepo(
        listOf("head"),
        mapOf("head" to diffFor("src/A.kt", "alpha")),
        mapOf("head" to "base"),
      )
    val aggregate = diffFor("src/A.kt", "alpha") + "\n" + diffFor("src/Dropped.kt", "gone")
    assertTrue("src/Dropped.kt" in unresolvedMessage(git, aggregate))
  }

  @Test fun `a duplicated commit fails loudly`() {
    val diff = diffFor("src/A.kt", "alpha")
    val git =
      FakeGit(
        commits = mapOf("base..head" to listOf("head", "head")),
        metadata = mapOf("head" to ReviewCommitMetadata(listOf("base"), "subject head")),
        diffs = mapOf(ReviewDiffQuery.CommitRange("base", "head") to diff),
      )
    assertTrue("more than once" in unresolvedMessage(git, diff))
  }

  @Test fun `identical hunks in two commits keep distinct commit-scoped identities`() {
    val duplicate = diffFor("src/A.kt", "alpha")
    val git =
      branchRepo(
        listOf("c1", "head"),
        mapOf("c1" to duplicate, "head" to duplicate),
        mapOf("c1" to "base", "head" to "c1"),
      )
    val resolved = resolve(git, ParallelReviewScope.BRANCH, duplicate)
    val ids = resolved.units.flatMap { it.hunkIds }
    assertEquals(2, ids.size)
    assertEquals(2, ids.distinct().size)
  }

  @Test fun `a failed rev-list fails loudly instead of degrading to a synthetic unit`() {
    assertTrue("enumerate the commit sequence" in unresolvedMessage(FakeGit(), diffFor("src/A.kt", "alpha")))
  }

  @Test fun `a sequence that does not reach head fails loudly`() {
    val git =
      branchRepo(
        listOf("c1"),
        mapOf("c1" to diffFor("src/A.kt", "alpha")),
        mapOf("c1" to "base"),
      )
    assertTrue(unresolvedMessage(git, diffFor("src/A.kt", "alpha")).isNotBlank())
  }

  @Test fun `non-commit and locally-absent sources produce exactly one declared synthetic unit`() {
    val aggregate = diffFor("src/A.kt", "alpha")
    val noGit = FakeGit()
    listOf(
      ParallelReviewScope.STAGED to ReviewCommitSource.SYNTHETIC_WORKING_TREE,
      ParallelReviewScope.UNSTAGED to ReviewCommitSource.SYNTHETIC_WORKING_TREE,
      ParallelReviewScope.UNCOMMITTED to ReviewCommitSource.SYNTHETIC_WORKING_TREE,
    ).forEach { (scope, expected) ->
      val resolved = resolve(noGit, scope, aggregate)
      assertEquals(1, resolved.units.size)
      assertEquals(expected, resolved.units.single().source)
      assertTrue(resolved.coverageFact.degradedReason!!.isNotBlank())
    }
    assertEquals(
      ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF,
      resolve(noGit, ParallelReviewScope.BRANCH, aggregate, supplied = true).units.single().source,
    )

    assertEquals(
      ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF,
      resolve(
        FakeGit(commits = mapOf("base..head" to emptyList())),
        ParallelReviewScope.PR,
        aggregate,
      ).units.single().source,
    )
  }
}
