package skillbill.application.reviewevidence

import skillbill.error.core.catchingNonCancellation
import skillbill.review.context.model.commit.ReviewCommitSource

internal object SharedReviewEvidenceCodec {
  private const val VERSION = "shared-review-evidence/1"

  internal fun encode(record: SharedReviewEvidenceRecord): String =
    buildString {
      val sequence = record.sequence
      field(VERSION)
      field(record.aggregateDiff)
      field(sequence.baseRevision)
      field(sequence.headRevision)
      field(sequence.syntheticSource?.name.orEmpty())
      field(sequence.syntheticReason.orEmpty())
      field(sequence.commits.size.toString())
      sequence.commits.forEach { commit ->
        field(commit.commitSha)
        field(commit.parentSha)
        field(commit.subject)
        field(commit.diff)
      }
    }

  internal fun decode(payload: String): SharedReviewEvidenceRecord? =
    catchingNonCancellation {
      val reader = FieldReader(payload)
      require(reader.next() == VERSION) { "Unknown shared review evidence payload version." }
      val aggregateDiff = reader.next()
      val baseRevision = reader.next()
      val headRevision = reader.next()
      val syntheticSource = reader.next().takeIf { it.isNotEmpty() }?.let(ReviewCommitSource::valueOf)
      val syntheticReason = reader.next().takeIf { it.isNotEmpty() }
      val commits =
        List(reader.next().toInt()) {
          RawCommitDiff(reader.next(), reader.next(), reader.next(), reader.next())
        }
      require(reader.atEnd) { "Shared review evidence payload carries trailing bytes." }
      SharedReviewEvidenceRecord(
        aggregateDiff = aggregateDiff,
        sequence =
          SharedReviewEvidenceCommits(
            baseRevision = baseRevision,
            headRevision = headRevision,
            commits = commits,
            syntheticSource = syntheticSource,
            syntheticReason = syntheticReason,
          ),
      )
    }.getOrNull()

  private fun StringBuilder.field(value: String) {
    append(value.length).append('\n').append(value).append('\n')
  }

  private class FieldReader(private val payload: String) {
    private var cursor = 0

    val atEnd: Boolean get() = cursor == payload.length

    fun next(): String {
      val separator = payload.indexOf('\n', cursor)
      require(separator > cursor) { "Shared review evidence payload field is missing its length prefix." }
      val length = payload.substring(cursor, separator).toInt()
      val start = separator + 1
      val end = start + length
      require(length >= 0 && end < payload.length && payload[end] == '\n') {
        "Shared review evidence payload field is truncated."
      }
      cursor = end + 1
      return payload.substring(start, end)
    }
  }
}
