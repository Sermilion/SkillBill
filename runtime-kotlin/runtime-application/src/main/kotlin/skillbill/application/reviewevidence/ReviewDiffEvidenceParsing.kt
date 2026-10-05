package skillbill.application.reviewevidence

import skillbill.application.reviewevidence.model.ReviewChangedFileEvidence
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.application.reviewevidence.model.ReviewDiffEvidenceParseResult
import skillbill.review.context.model.hunk.ReviewChangedHunk

internal fun parseAttributableReviewDiffEvidence(diff: String): ReviewDiffEvidence? =
  diffRecords(diff).takeIf { it.isNotEmpty() }?.let {
    (
      parseReviewDiffEvidenceOrRejection(it.joinToString("\n")) as? ReviewDiffEvidenceParseResult.Parsed
    )?.evidence
  }

internal sealed interface ReviewDiffParseOutcome<out T> {
  data class Accepted<T>(val value: T) : ReviewDiffParseOutcome<T>

  data class Rejected(val reason: String) : ReviewDiffParseOutcome<Nothing>
}

internal fun parseReviewDiffEvidenceOrRejection(diff: String): ReviewDiffEvidenceParseResult =
  when (val result = parseReviewDiffEvidenceValue(diff)) {
    is ReviewDiffParseOutcome.Accepted -> ReviewDiffEvidenceParseResult.Parsed(result.value)
    is ReviewDiffParseOutcome.Rejected -> ReviewDiffEvidenceParseResult.Rejected(result.reason)
  }

internal inline fun <T, R> ReviewDiffParseOutcome<T>.flatMap(
  transform: (T) -> ReviewDiffParseOutcome<R>,
): ReviewDiffParseOutcome<R> =
  when (this) {
    is ReviewDiffParseOutcome.Accepted -> transform(value)
    is ReviewDiffParseOutcome.Rejected -> this
  }

private fun parseReviewDiffEvidenceValue(diff: String): ReviewDiffParseOutcome<ReviewDiffEvidence> {
  val records = diffRecords(diff.replace("\r\n", "\n"))
  if (records.isEmpty()) {
    return ReviewDiffParseOutcome.Rejected("The authoritative review diff contains no attributable diff records.")
  }
  val hunks = mutableListOf<ReviewChangedHunk>()
  val files = mutableListOf<ReviewChangedFileEvidence>()
  for (record in records) {
    when (val result = parseReviewDiffRecord(record, hunks)) {
      is ReviewDiffParseOutcome.Accepted -> files += result.value
      is ReviewDiffParseOutcome.Rejected -> return result
    }
  }
  return if (hunks.isEmpty()) {
    ReviewDiffParseOutcome.Rejected("The authoritative review diff contains no parseable changed hunks.")
  } else {
    ReviewDiffParseOutcome.Accepted(ReviewDiffEvidence(hunks, files))
  }
}

private fun parseReviewDiffRecord(
  record: String,
  hunks: MutableList<ReviewChangedHunk>,
): ReviewDiffParseOutcome<ReviewChangedFileEvidence> =
  reviewDiffRecordPaths(record).flatMap { paths ->
    val changedContent =
      record.lineSequence()
        .filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
        .joinToString("\n")
    parseReviewDiffHunks(record, paths.authoritative).flatMap { parsedHunks ->
      hunks += parsedHunks
      ReviewDiffParseOutcome.Accepted(
        ReviewChangedFileEvidence(paths.authoritative, changedContent, record.trimEnd(), paths.old, paths.new),
      )
    }
  }

private fun parseReviewDiffHunks(
  record: String,
  path: String,
): ReviewDiffParseOutcome<List<ReviewChangedHunk>> {
  val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
  val lines = record.lines()
  val hunks = mutableListOf<ReviewChangedHunk>()
  var index = 0
  while (index < lines.size) {
    val match = header.find(lines[index])
    if (match == null) {
      index += 1
      continue
    }
    val coordinates =
      when (val result = parseReviewDiffCoordinates(match)) {
        is ReviewDiffParseOutcome.Accepted -> result.value
        is ReviewDiffParseOutcome.Rejected -> return result
      }
    val oldStart = coordinates[REVIEW_DIFF_OLD_START_GROUP - 1]
    val oldCount = coordinates[REVIEW_DIFF_OLD_COUNT_GROUP - 1]
    val newStart = coordinates[REVIEW_DIFF_NEW_START_GROUP - 1]
    val newCount = coordinates[REVIEW_DIFF_NEW_COUNT_GROUP - 1]
    val content =
      buildString {
        appendLine(lines[index++])
        while (index < lines.size && !lines[index].startsWith("@@ ")) appendLine(lines[index++])
      }.removeSuffix("\n")
    hunks += ReviewChangedHunk(path, oldStart, oldCount, newStart, newCount, content)
  }
  if (hunks.isEmpty()) hunks += ReviewChangedHunk(path, 0, 0, 0, 0, record.trimEnd())
  return ReviewDiffParseOutcome.Accepted(hunks)
}

private fun parseReviewDiffCoordinates(match: MatchResult): ReviewDiffParseOutcome<List<Int>> {
  val raw =
    listOf(
      match.groupValues[REVIEW_DIFF_OLD_START_GROUP],
      match.groupValues[REVIEW_DIFF_OLD_COUNT_GROUP].ifBlank { "1" },
      match.groupValues[REVIEW_DIFF_NEW_START_GROUP],
      match.groupValues[REVIEW_DIFF_NEW_COUNT_GROUP].ifBlank { "1" },
    )
  val invalid = raw.firstOrNull { it.toIntOrNull() == null }
  if (invalid != null) return ReviewDiffParseOutcome.Rejected("For input string: \"$invalid\"")
  val coordinates = raw.map(String::toInt)
  return if (coordinates.any { it < 0 }) {
    ReviewDiffParseOutcome.Rejected("Failed requirement.")
  } else {
    ReviewDiffParseOutcome.Accepted(coordinates)
  }
}
