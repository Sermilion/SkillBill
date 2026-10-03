package skillbill.application.review.packet

import java.nio.file.Path
import skillbill.application.reviewevidence.SharedReviewEvidenceCodec
import skillbill.application.reviewevidence.SharedReviewEvidenceCommits
import skillbill.application.reviewevidence.SharedReviewEvidenceRecord
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.error.shellcontent.reviewHunkEvidenceIntegrityError
import skillbill.error.shellcontent.reviewHunkEvidenceLocatorMissingError
import skillbill.error.shellcontent.reviewHunkEvidenceLocatorUnreadableError
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.review.context.model.commit.ReviewCommitSource
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewHunkEvidenceLocator
import skillbill.text.RECORD_FIELD_SEPARATOR

internal data class IndexedReviewHunks(
  val hunks: List<ReviewChangedHunk>,
  val commitUnits: List<ReviewCommitUnit>,
)

internal object ReviewHunkStoreIndexing {
  internal fun index(
    hunks: List<ReviewChangedHunk>,
    commitUnits: List<ReviewCommitUnit>,
    storePath: String?,
    repoRoot: Path?,
    locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
  ): IndexedReviewHunks {
    if (storePath.isNullOrBlank()) {
      if (locatorReader != null) {
        throw reviewHunkEvidenceLocatorMissingError(storePath.orEmpty())
      }
      return IndexedReviewHunks(
        hunks = hunks.map { it.asIndex(it.evidenceLocator, it.content) },
        commitUnits =
          commitUnits.map { unit ->
            unit.copy(hunks = unit.hunks.map { it.asIndex(it.evidenceLocator, it.content) })
          },
      )
    }
    val payload = readLocatorPayload(storePath, repoRoot, locatorReader)
    val record = SharedReviewEvidenceCodec.decode(payload) ?: rawRecord(payload, storePath)
    val indexed = hunks.map { hunk -> indexHunk(hunk, record, storePath) }
    val byKey = indexed.associateBy { hunkKey(it) }
    return IndexedReviewHunks(
      hunks = indexed,
      commitUnits =
        commitUnits.map { unit ->
          unit.copy(
            hunks =
              unit.hunks.map { incoming ->
                byKey[hunkKey(incoming)] ?: indexHunk(incoming, record, storePath)
              },
          )
        },
    )
  }

  private fun readLocatorPayload(
    storePath: String,
    repoRoot: Path?,
    locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
  ): String {
    val root =
      repoRoot ?: throw reviewHunkEvidenceLocatorUnreadableError(
        storePath,
        "compose-time locator dereference requires a repository root",
      )
    return locatorReader?.readDiffPayload(
      FeatureTaskRuntimeSharedEvidenceLocatorReadRequest(root, storePath),
    ) ?: throw reviewHunkEvidenceLocatorMissingError(storePath)
  }

  private fun rawRecord(
    payload: String,
    storePath: String,
  ): SharedReviewEvidenceRecord {
    runCatching { ReviewDiffEvidence.parse(payload) }.getOrNull()
      ?: throw reviewHunkEvidenceLocatorUnreadableError(
        storePath,
        "payload is not a shared-evidence or git-diff body",
      )
    return SharedReviewEvidenceRecord(
      aggregateDiff = payload,
      sequence =
        SharedReviewEvidenceCommits(
          baseRevision = "unknown",
          headRevision = "unknown",
          commits = emptyList(),
          syntheticSource = ReviewCommitSource.SYNTHETIC_SUPPLIED_DIFF,
          syntheticReason = "compose-time locator payload was a raw diff",
        ),
    )
  }

  private fun indexHunk(
    hunk: ReviewChangedHunk,
    record: SharedReviewEvidenceRecord,
    storePath: String,
  ): ReviewChangedHunk {
    val storedBody = storedBody(hunk, record, storePath)
    val storedDigest = ReviewChangedHunk.digestOfBody(storedBody)
    val expectedDigest = claimedDigest(hunk, storePath)
    if (expectedDigest != null && expectedDigest != storedDigest) {
      throw reviewHunkEvidenceIntegrityError(storePath, expectedDigest, storedDigest)
    }
    val locator =
      ReviewHunkEvidenceLocator.atStore(
        storePath,
        hunk.oldStart,
        hunk.oldCount,
        hunk.newStart,
        hunk.newCount,
      )
    return hunk.asIndex(locator, storedBody)
  }

  fun extractStoredBody(
    hunk: ReviewChangedHunk,
    payload: String,
    storePath: String,
  ): String {
    val record = SharedReviewEvidenceCodec.decode(payload) ?: rawRecord(payload, storePath)
    return storedBody(hunk, record, storePath)
  }

  private fun storedBody(
    hunk: ReviewChangedHunk,
    record: SharedReviewEvidenceRecord,
    storePath: String,
  ): String {
    val scoped = hunk.commitScope
    val body =
      if (scoped != null) {
        val sha = scoped.substringBefore('@')
        hunkBodyIn(record.sequence.commits.find { it.commitSha == sha }?.diff, hunk)
      } else {
        hunkBodyIn(record.aggregateDiff, hunk)
      }
    return body ?: throw reviewHunkEvidenceLocatorUnreadableError(
      storePath,
      "stored payload has no hunk at ${hunk.path} " +
        ReviewHunkEvidenceLocator.header(hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount),
    )
  }

  private fun claimedDigest(
    hunk: ReviewChangedHunk,
    storePath: String,
  ): String? =
    when {
      hunk.content.isNotEmpty() -> ReviewChangedHunk.digestOfBody(hunk.content)
      hunk.evidenceLocator.storePath == storePath -> hunk.contentDigest
      else -> null
    }

  private fun hunkBodyIn(
    diff: String?,
    hunk: ReviewChangedHunk,
  ): String? =
    diff?.let { payload ->
      runCatching { ReviewDiffEvidence.parse(payload) }.getOrNull()
        ?.hunks
        ?.find { sameSpan(it, hunk) }
        ?.content
    }

  private fun sameSpan(
    left: ReviewChangedHunk,
    right: ReviewChangedHunk,
  ): Boolean =
    left.path == right.path &&
      left.oldStart == right.oldStart &&
      left.oldCount == right.oldCount &&
      left.newStart == right.newStart &&
      left.newCount == right.newCount

  private fun hunkKey(hunk: ReviewChangedHunk): String =
    listOf(hunk.path, hunk.oldStart, hunk.oldCount, hunk.newStart, hunk.newCount, hunk.commitScope.orEmpty())
      .joinToString(RECORD_FIELD_SEPARATOR)
}
