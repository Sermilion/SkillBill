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

private fun parseReviewDiffEvidenceValue(diff: String): ReviewDiffParseOutcome<ReviewDiffEvidence> {
  val normalized = diff.replace("\r\n", "\n")
  val records = diffRecords(normalized)
  if (records.isEmpty()) {
    return ReviewDiffParseOutcome.Rejected("The authoritative review diff contains no attributable diff records.")
  }
  val hunks = mutableListOf<ReviewChangedHunk>()
  val files =
    records.map { record ->
      val paths =
        when (val result = reviewDiffRecordPaths(record)) {
          is ReviewDiffParseOutcome.Accepted -> result.value
          is ReviewDiffParseOutcome.Rejected -> return result
        }
      val path = paths.authoritative
      val changedContent =
        record.lineSequence()
          .filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
          .joinToString("\n")
      val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
      val lines = record.lines()
      var index = 0
      var found = false
      while (index < lines.size) {
        val match = header.find(lines[index])
        if (match == null) {
          index += 1
          continue
        }
        found = true
        val oldStart = match.groupValues[REVIEW_DIFF_OLD_START_GROUP].toIntOrNull()
        val oldCount = match.groupValues[REVIEW_DIFF_OLD_COUNT_GROUP].ifBlank { "1" }.toIntOrNull()
        val newStart = match.groupValues[REVIEW_DIFF_NEW_START_GROUP].toIntOrNull()
        val newCount = match.groupValues[REVIEW_DIFF_NEW_COUNT_GROUP].ifBlank { "1" }.toIntOrNull()
        if (oldStart == null || oldCount == null || newStart == null || newCount == null) {
          val invalidCoordinate =
            listOf(
              match.groupValues[REVIEW_DIFF_OLD_START_GROUP],
              match.groupValues[REVIEW_DIFF_OLD_COUNT_GROUP].ifBlank { "1" },
              match.groupValues[REVIEW_DIFF_NEW_START_GROUP],
              match.groupValues[REVIEW_DIFF_NEW_COUNT_GROUP].ifBlank { "1" },
            ).first { it.toIntOrNull() == null }
          return ReviewDiffParseOutcome.Rejected("For input string: \"$invalidCoordinate\"")
        }
        if (oldStart < 0 || oldCount < 0 || newStart < 0 || newCount < 0) {
          return ReviewDiffParseOutcome.Rejected("Failed requirement.")
        }
        val content =
          buildString {
            appendLine(lines[index++])
            while (index < lines.size && !lines[index].startsWith("@@ ")) appendLine(lines[index++])
          }.removeSuffix("\n")
        hunks +=
          ReviewChangedHunk(
            path,
            oldStart,
            oldCount,
            newStart,
            newCount,
            content,
          )
      }
      if (!found) hunks += ReviewChangedHunk(path, 0, 0, 0, 0, record.trimEnd())
      ReviewChangedFileEvidence(path, changedContent, record.trimEnd(), paths.old, paths.new)
    }
  if (hunks.isEmpty()) {
    return ReviewDiffParseOutcome.Rejected("The authoritative review diff contains no parseable changed hunks.")
  }
  return ReviewDiffParseOutcome.Accepted(ReviewDiffEvidence(hunks, files))
}
