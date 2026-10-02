package skillbill.engine.featuretask.review.core

import skillbill.error.featuretask.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.ports.diff.DiffResolverPortDefaults
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeSharedReviewEvidenceResolverTest {
  private val repoRoot: Path = Path.of(".")

  private fun diffFor(
    path: String,
    line: String,
    hunks: Int = 1,
  ) = buildString {
    appendLine("diff --git a/$path b/$path")
    appendLine("--- a/$path")
    appendLine("+++ b/$path")
    repeat(hunks) { index ->
      appendLine("@@ -$index,1 +$index,1 @@")
      appendLine("+$line-$index")
    }
  }

  private class FakeGit(private val responses: Map<ReviewDiffQuery, String?>) : DiffResolverPortDefaults() {
    val invoked: MutableList<ReviewDiffQuery> = mutableListOf()

    override fun diff(
      repoRoot: Path,
      query: ReviewDiffQuery,
    ): String? {
      invoked += query
      return responses[query]
    }

    override fun reviewWorktreeFileIdentities(
      root: Path,
      paths: List<String>,
    ) = emptyMap<String, ReviewCheckpointFileIdentity>()
  }

  private class InterruptedGit : DiffResolverPortDefaults() {
    override fun diff(
      repoRoot: Path,
      query: ReviewDiffQuery,
    ): String? = throw InterruptedException("interrupted while reading the diff")
  }

  private class InMemoryStore : FeatureTaskRuntimeSharedEvidenceResolverPort {
    private val stored = mutableMapOf<String, FeatureTaskRuntimeSharedEvidenceResolution>()
    var derivations: Int = 0
      private set

    fun holds(fingerprint: String): Boolean = fingerprint in stored

    override fun resolve(
      request: FeatureTaskRuntimeSharedEvidenceRequest,
      deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
    ): FeatureTaskRuntimeSharedEvidenceResolution? {
      val fingerprint = request.checkpoint.fingerprint
      stored[fingerprint]?.let {
        return it.copy(outcome = REUSE)
      }
      derivations++
      val derivation = deriver.derive(request.checkpoint) ?: return null
      val outcome =
        if (stored.isNotEmpty()) {
          CHECKPOINT_CHANGE_REDERIVATION
        } else {
          DERIVATION
        }
      val resolution =
        FeatureTaskRuntimeSharedEvidenceResolution(
          artifact =
            FeatureTaskRuntimeSharedEvidenceArtifact(
              fingerprint = fingerprint,
              baseRef = derivation.baseRef,
              headRef = derivation.headRef,
              files = derivation.files,
              hunks = derivation.hunks,
              diffPayload =
                FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
                  "diff.patch",
                  derivation.diffPayload.length.toLong(),
                ),
            ),
          diffPayload = derivation.diffPayload,
          storePath = ".skill-bill/run-evidence/${request.workflowId}/$fingerprint",
          outcome = outcome,
        )
      stored[fingerprint] = resolution
      return resolution
    }
  }

  private fun checkpoint(
    fingerprint: String,
    base: String = "base",
    head: String = "head",
  ) = FeatureTaskRuntimeRepositoryCheckpoint(
    fingerprint = fingerprint,
    baseRef = base,
    headRef = head,
  )

  private fun gitFor(
    base: String,
    head: String,
    path: String,
    line: String,
    hunks: Int = 1,
  ) = FakeGit(mapOf(ReviewDiffQuery.CommitRange(base, head) to diffFor(path, line, hunks)))

  @Test
  fun `an absent artifact re-derives and the launch still receives a reference`() {
    val store = InMemoryStore()
    val git = gitFor("base", "head", "a.kt", "added")
    val resolver = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, git)

    val reference = resolver.resolve(repoRoot, "wf-1", checkpoint("fp-absent"), "audit")?.reference

    assertNotNull(reference)
    assertEquals(1, store.derivations)
    assertEquals(".skill-bill/run-evidence/wf-1/fp-absent", reference.storePath)
    assertEquals("fp-absent", reference.checkpointFingerprint)
    assertEquals(1, reference.changedFileCount)
    assertEquals(1, reference.changedHunkCount)
  }

  @Test
  fun `a resolvable artifact at a matching fingerprint is reused with no repository traversal`() {
    val store = InMemoryStore()
    val firstGit = gitFor("base", "head", "a.kt", "one")
    val resolver = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, firstGit)
    val first = resolver.resolve(repoRoot, "wf-1", checkpoint("fp-hit"), "audit")
    assertNotNull(first)
    assertEquals(1, store.derivations)
    assertEquals(1, firstGit.invoked.size)

    val secondGit = FakeGit(emptyMap())
    val reused =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, secondGit)
        .resolve(repoRoot, "wf-1", checkpoint("fp-hit"), "audit")

    assertNotNull(reused)
    assertEquals(first.reference.storePath, reused.reference.storePath)
    assertEquals(first.reference.checkpointFingerprint, reused.reference.checkpointFingerprint)
    assertEquals(1, store.derivations, "a fingerprint hit must not re-derive")
    assertTrue(secondGit.invoked.isEmpty(), "a fingerprint hit must not touch the repository")
  }

  @Test
  fun `audit_gap re-entry at an unchanged checkpoint reuses and a moved tree re-derives`() {
    val store = InMemoryStore()
    val firstGit = gitFor("base", "head", "a.kt", "before")
    val resolver = FeatureTaskRuntimeSharedReviewEvidenceResolver(store, firstGit)
    val before = resolver.resolve(repoRoot, "wf-audit-gap", checkpoint("fp-unchanged"), "audit")
    assertNotNull(before)

    val reuseGit = FakeGit(emptyMap())
    val reused =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, reuseGit)
        .resolve(repoRoot, "wf-audit-gap", checkpoint("fp-unchanged"), "audit")
    assertNotNull(reused)
    assertEquals(before.reference.checkpointFingerprint, reused.reference.checkpointFingerprint)
    assertEquals(1, store.derivations)
    assertTrue(reuseGit.invoked.isEmpty())

    val movedGit = gitFor("base", "head", "b.kt", "after")
    val moved =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, movedGit)
        .resolve(repoRoot, "wf-audit-gap", checkpoint("fp-moved"), "audit")
    assertNotNull(moved)
    assertNotEquals(before.reference.checkpointFingerprint, moved.reference.checkpointFingerprint)
    assertEquals(2, store.derivations)
    assertEquals(1, movedGit.invoked.size)
  }

  @Test
  fun `review_fix re-entry reuses or re-derives by fingerprint with no added invalidation branch`() {
    val store = InMemoryStore()
    val first =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(
        store,
        gitFor("base", "head", "fix.kt", "round-1"),
      ).resolve(repoRoot, "wf-review-fix", checkpoint("fp-review-1"), "audit")
    assertNotNull(first)

    val same =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, FakeGit(emptyMap()))
        .resolve(repoRoot, "wf-review-fix", checkpoint("fp-review-1"), "audit")
    assertNotNull(same)
    assertEquals(first.reference.checkpointFingerprint, same.reference.checkpointFingerprint)
    assertEquals(1, store.derivations)

    val next =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(
        store,
        gitFor("base", "head", "fix.kt", "round-2"),
      ).resolve(repoRoot, "wf-review-fix", checkpoint("fp-review-2"), "audit")
    assertNotNull(next)
    assertNotEquals(first.reference.checkpointFingerprint, next.reference.checkpointFingerprint)
    assertEquals(2, store.derivations)
  }

  @Test
  fun `a resolution that cannot produce a store path yields null so the launch still succeeds`() {
    val blankStore =
      FeatureTaskRuntimeSharedEvidenceResolverPort { request, deriver ->
        val derivation =
          deriver.derive(request.checkpoint)
            ?: return@FeatureTaskRuntimeSharedEvidenceResolverPort null
        FeatureTaskRuntimeSharedEvidenceResolution(
          artifact =
            FeatureTaskRuntimeSharedEvidenceArtifact(
              fingerprint = request.checkpoint.fingerprint,
              baseRef = derivation.baseRef,
              headRef = derivation.headRef,
              files = derivation.files,
              hunks = derivation.hunks,
              diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", 0),
            ),
          diffPayload = derivation.diffPayload,
          storePath = null,
        )
      }
    val reference =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(
        blankStore,
        gitFor("base", "head", "a.kt", "x"),
      ).resolve(repoRoot, "wf-1", checkpoint("fp"), "audit")

    assertNull(reference)
  }

  @Test
  fun `a fingerprint contradiction from the port loud-fails instead of becoming a silent null omit`() {
    val contradicted =
      FeatureTaskRuntimeSharedEvidenceResolverPort { _, _ ->
        throw FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError(
          addressedFingerprint = "fp-addressed",
          recordedFingerprint = "fp-recorded",
          sourceLabel = "envelope.json",
        )
      }
    val error =
      assertFailsWith<FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError> {
        FeatureTaskRuntimeSharedReviewEvidenceResolver(
          contradicted,
          FakeGit(emptyMap()),
        ).resolve(repoRoot, "wf-1", checkpoint("fp-addressed"), "audit")
      }
    assertEquals("fp-addressed", error.addressedFingerprint)
    assertEquals("fp-recorded", error.recordedFingerprint)
  }

  @Test
  fun `serialized reference size tracks file count not diff size`() {
    val store = InMemoryStore()

    fun resolve(hunks: Int) =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(
        store,
        gitFor("base", "head", "a.kt", "line", hunks),
      ).resolve(repoRoot, "wf-size", checkpoint("fp-$hunks"), "audit")!!

    val small = resolve(1)
    val large = resolve(200)
    assertEquals(small.reference.changedFileCount, large.reference.changedFileCount)
    assertTrue(small.reference.changedHunkCount < large.reference.changedHunkCount)
    assertEquals(64, small.reference.fileHunkIndexDigest.length)
    assertEquals(64, large.reference.fileHunkIndexDigest.length)
  }

  @Test
  fun `owned worktree paths derive against the base revision`() {
    val store = InMemoryStore()
    val workingTree = ReviewDiffQuery.WorkingTree("base", listOf("src/A.kt"), includeBinary = false)
    val git = FakeGit(mapOf(workingTree to diffFor("src/A.kt", "working")))
    val checkpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(
        fingerprint = "fp-working-tree",
        baseRef = "base",
        headRef = "checkpoint-head",
        workingTreeOwnedPaths = listOf("src/A.kt"),
      )

    val reference =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, git)
        .resolve(repoRoot, "wf-working-tree", checkpoint, "audit")

    assertNotNull(reference)
    assertEquals(listOf<ReviewDiffQuery>(workingTree), git.invoked)
    assertEquals(1, reference.reference.changedFileCount)
  }

  @Test
  fun `an unavailable diff yields no resolution and stores no artifact`() {
    val store = InMemoryStore()
    val git = FakeGit(mapOf(ReviewDiffQuery.CommitRange("base", "head") to null))

    val resolution =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, git)
        .resolve(repoRoot, "wf-unavailable", checkpoint("fp-unavailable"), "audit")

    assertNull(resolution)
    assertFalse(store.holds("fp-unavailable"))
  }

  @Test
  fun `an empty successful diff yields a reference with no files`() {
    val store = InMemoryStore()
    val git = FakeGit(mapOf(ReviewDiffQuery.CommitRange("base", "head") to ""))

    val resolution =
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, git)
        .resolve(repoRoot, "wf-empty", checkpoint("fp-empty"), "audit")

    assertNotNull(resolution)
    assertEquals(0, resolution.reference.changedFileCount)
    assertTrue(store.holds("fp-empty"))
  }

  @Test
  fun `an interrupted diff query propagates instead of becoming an absent resolution`() {
    val store = InMemoryStore()

    assertFailsWith<InterruptedException> {
      FeatureTaskRuntimeSharedReviewEvidenceResolver(store, InterruptedGit())
        .resolve(repoRoot, "wf-interrupted", checkpoint("fp-interrupted"), "audit")
    }
    assertFalse(store.holds("fp-interrupted"))
  }
}
