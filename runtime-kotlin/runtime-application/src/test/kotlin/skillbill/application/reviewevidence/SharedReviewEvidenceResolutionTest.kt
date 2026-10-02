package skillbill.application.reviewevidence

import skillbill.application.reviewevidence.model.DiffResolution
import skillbill.application.reviewevidence.model.ParallelReviewScope
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.DiffResolverPortDefaults
import skillbill.ports.diff.model.ReviewCommitMetadata
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.taskruntime.DERIVING_SHARED_EVIDENCE_RESOLVER
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.review.context.model.commit.REVIEW_SYNTHETIC_COMMIT_PREFIX
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedReviewEvidenceResolutionTest {
  private val repoRoot: Path = Path.of(".")
  private val range = ReviewCommitRange("base", "head")

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

  private class InMemoryStore(
    private val corruptPayload: Boolean = false,
  ) : FeatureTaskRuntimeSharedEvidenceResolverPort {
    private val stored = mutableMapOf<String, FeatureTaskRuntimeSharedEvidenceResolution>()
    var derivations: Int = 0
      private set

    override fun resolve(
      request: FeatureTaskRuntimeSharedEvidenceRequest,
      deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
    ): FeatureTaskRuntimeSharedEvidenceResolution? {
      val fingerprint = request.checkpoint.fingerprint
      stored[fingerprint]?.let { return it }
      derivations++
      val derivation = deriver.derive(request.checkpoint) ?: return null
      val payload = if (corruptPayload) "corrupted cache entry" else derivation.diffPayload
      val resolution =
        FeatureTaskRuntimeSharedEvidenceResolution(
          artifact =
            FeatureTaskRuntimeSharedEvidenceArtifact(
              fingerprint = fingerprint,
              baseRef = derivation.baseRef,
              headRef = derivation.headRef,
              files = derivation.files,
              hunks = derivation.hunks,
              diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", payload.length.toLong()),
            ),
          diffPayload = payload,
          storePath = ".skill-bill/run-evidence/${request.workflowId}/$fingerprint",
        )
      stored[fingerprint] = resolution
      return resolution
    }
  }

  private fun branchGit(
    shas: List<String>,
    parents: Map<String, String>,
    diffs: Map<String, String>,
  ): FakeGit =
    FakeGit(
      commits = mapOf("base..head" to shas),
      metadata = shas.associateWith { sha -> ReviewCommitMetadata(listOf(parents.getValue(sha)), "subject $sha") },
      diffs = shas.associate { sha -> ReviewDiffQuery.CommitRange(parents.getValue(sha), sha) to diffs.getValue(sha) },
    )

  private fun twoCommitGit(): Pair<FakeGit, String> {
    val diffs = mapOf("c1" to diffFor("src/c1.kt", "one"), "head" to diffFor("src/head.kt", "two"))
    val git = branchGit(listOf("c1", "head"), mapOf("c1" to "base", "head" to "c1"), diffs)
    return git to (diffs.getValue("c1") + "\n" + diffs.getValue("head"))
  }

  private fun queryOf(
    scope: ParallelReviewScope = ParallelReviewScope.BRANCH,
    supplied: Boolean = false,
    workflowId: String = "wf-1",
  ) = SharedReviewEvidenceQuery(repoRoot, workflowId, scope, range, supplied)

  private fun resolve(
    store: FeatureTaskRuntimeSharedEvidenceResolverPort,
    git: DiffResolverPort,
    aggregate: String,
    query: SharedReviewEvidenceQuery = queryOf(),
    aggregateReads: MutableList<String>? = null,
  ): SharedReviewEvidenceRecord =
    assertIs<DiffResolution.Resolved<SharedReviewEvidenceRecord>>(
      SharedReviewEvidenceResolution(store, git).resolve(query) {
        aggregateReads?.add("aggregate")
        DiffResolution.Resolved(aggregate)
      },
    ).value

  private fun projectResolved(
    record: SharedReviewEvidenceCommits,
    aggregate: ReviewDiffEvidence,
  ): ResolvedCommitSequence =
    assertIs<DiffResolution.Resolved<ResolvedCommitSequence>>(
      SharedReviewEvidenceProjection.project(record, aggregate),
    ).value

  @Test fun `a fingerprint hit serves the stored evidence with zero repository traversal`() {
    val store = InMemoryStore()
    val (first, aggregate) = twoCommitGit()
    val reads = mutableListOf<String>()

    val derived = resolve(store, first, aggregate, aggregateReads = reads)
    val lanes =
      (1..4).map {
        val lane = twoCommitGit().first
        resolve(store, lane, aggregate, aggregateReads = reads) to lane
      }

    assertEquals(1, store.derivations)
    assertEquals(1, reads.size)
    assertTrue(lanes.all { it.second.invoked.isEmpty() }, "a lane reading the stored artifact must not shell out")
    assertTrue(lanes.all { it.first == derived })
  }

  @Test fun `identities projected from stored evidence match in-line derivation byte for byte`() {
    val store = InMemoryStore()
    val (git, aggregate) = twoCommitGit()
    val parsed = ReviewDiffEvidence.parse(aggregate)

    val inLine =
      projectResolved(
        resolve(DERIVING_SHARED_EVIDENCE_RESOLVER, git, aggregate).sequence,
        parsed,
      )
    resolve(store, twoCommitGit().first, aggregate)
    val fromStore =
      projectResolved(
        resolve(store, twoCommitGit().first, aggregate).sequence,
        parsed,
      )

    assertEquals(inLine.units.map { it.commitUnitId }, fromStore.units.map { it.commitUnitId })
    assertEquals(inLine.units.flatMap { it.hunkIds }, fromStore.units.flatMap { it.hunkIds })
    assertEquals(inLine.coverageFact, fromStore.coverageFact)
    assertEquals(2, fromStore.units.size)
  }

  @Test fun `every synthetic source keeps its placeholder identity and sole-unit ordering`() {
    val aggregate = diffFor("src/A.kt", "alpha")
    val parsed = ReviewDiffEvidence.parse(aggregate)
    val cases =
      listOf(
        Triple(ParallelReviewScope.STAGED, false, ReviewCommitSource.SYNTHETIC_WORKING_TREE),
        Triple(ParallelReviewScope.UNCOMMITTED, false, ReviewCommitSource.SYNTHETIC_WORKING_TREE),
        Triple(ParallelReviewScope.BRANCH, true, ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF),
        Triple(ParallelReviewScope.PR, false, ReviewCommitSource.SYNTHETIC_AGGREGATE_PR_DIFF),
      )

    cases.forEach { (scope, supplied, expected) ->
      val store = InMemoryStore()
      val git = FakeGit(commits = mapOf("base..head" to emptyList()))
      val query = queryOf(scope = scope, supplied = supplied, workflowId = scope.name)
      val first = resolve(store, git, aggregate, query)
      val reloaded = resolve(store, git, aggregate, query)

      val projected = projectResolved(reloaded.sequence, parsed)
      val unit = projected.units.single()
      assertEquals(expected, unit.source, scope.name)
      assertTrue(unit.commitSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX), unit.commitSha)
      assertTrue(unit.parentSha.startsWith(REVIEW_SYNTHETIC_COMMIT_PREFIX), unit.parentSha)
      assertEquals(0, unit.orderIndex)
      assertEquals(
        projectResolved(first.sequence, parsed).coverageFact.degradedReason,
        projected.coverageFact.degradedReason,
      )
    }
  }

  @Test fun `a corrupt stored payload degrades to in-line derivation rather than failing the review`() {
    val store = InMemoryStore(corruptPayload = true)
    val (seed, aggregate) = twoCommitGit()
    resolve(store, seed, aggregate)
    val fallback = twoCommitGit().first

    val record = resolve(store, fallback, aggregate)

    val projected = projectResolved(record.sequence, ReviewDiffEvidence.parse(aggregate))
    assertTrue(fallback.invoked.isNotEmpty(), "a corrupt cache entry must re-derive")
    assertEquals(2, projected.units.size)
  }

  @Test fun `only an immutable commit range is checkpoint-keyed`() {
    val store = InMemoryStore()
    val aggregate = diffFor("src/A.kt", "alpha")
    val noGit = FakeGit(commits = mapOf("base..head" to emptyList()))

    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.BRANCH))
    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.PR))
    val branchAndPr = store.derivations
    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.STAGED))
    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.UNSTAGED))
    resolve(store, noGit, aggregate, queryOf(scope = ParallelReviewScope.UNCOMMITTED))

    assertEquals(2, branchAndPr)
    assertTrue(store.derivations > branchAndPr)
  }

  @Test fun `a supplied diff review derives in line rather than reusing a range-keyed artifact`() {
    val store = InMemoryStore()
    val noGit = FakeGit(commits = mapOf("base..head" to emptyList()))
    val first = diffFor("src/A.kt", "alpha")
    val second = diffFor("src/B.kt", "beta")

    val firstRecord = resolve(store, noGit, first, queryOf(supplied = true))
    val secondRecord = resolve(store, noGit, second, queryOf(supplied = true))

    assertEquals(2, store.derivations)
    assertEquals(first, firstRecord.aggregateDiff)
    assertEquals(second, secondRecord.aggregateDiff)
    assertTrue(firstRecord.storePath!!.startsWith(".skill-bill/run-evidence/wf-1/"))
    assertTrue(secondRecord.storePath!!.startsWith(".skill-bill/run-evidence/wf-1/"))
    assertNotEquals(firstRecord.storePath, secondRecord.storePath)
  }

  @Test fun `a different range resolves a different checkpoint`() {
    val store = InMemoryStore()
    val (git, aggregate) = twoCommitGit()
    resolve(store, git, aggregate)

    val otherGit = FakeGit(commits = mapOf("base..other-head" to emptyList()))
    val otherRange =
      assertIs<DiffResolution.Resolved<SharedReviewEvidenceRecord>>(
        SharedReviewEvidenceResolution(store, otherGit).resolve(
          SharedReviewEvidenceQuery(
            repoRoot = repoRoot,
            workflowId = "wf-1",
            scope = ParallelReviewScope.BRANCH,
            range = ReviewCommitRange("base", "other-head"),
            suppliedDiff = false,
          ),
        ) { DiffResolution.Resolved(aggregate) },
      ).value

    assertEquals(2, store.derivations)
    assertNotEquals("head", otherRange.sequence.headRevision)
  }

  @Test fun `standalone and feature-task locators share the run-evidence workflow layout`() {
    val store = InMemoryStore()
    val (git, aggregate) = twoCommitGit()
    val standalone = resolve(store, git, aggregate, queryOf(workflowId = "code-review"))
    val featureTask =
      resolve(
        InMemoryStore(),
        twoCommitGit().first,
        aggregate,
        queryOf(workflowId = "wftr-1"),
      )
    val standalonePath = checkNotNull(standalone.storePath)
    val featureTaskPath = checkNotNull(featureTask.storePath)
    assertTrue(standalonePath.startsWith(".skill-bill/run-evidence/code-review/"))
    assertTrue(featureTaskPath.startsWith(".skill-bill/run-evidence/wftr-1/"))
    assertTrue(standalonePath.endsWith(standalonePath.substringAfterLast('/')))
    assertEquals(
      standalonePath.count { it == '/' },
      featureTaskPath.count { it == '/' },
    )
  }
}
