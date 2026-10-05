package skillbill.application.reviewevidence.model

import skillbill.application.reviewevidence.parseAttributableReviewDiffEvidence
import skillbill.application.reviewevidence.parseReviewDiffEvidenceOrRejection
import skillbill.review.context.model.hunk.ReviewChangedHunk

data class ReviewDiffEvidence(
  val hunks: List<ReviewChangedHunk>,
  val files: List<ReviewChangedFileEvidence>,
) {
  init {
    require(hunks.isNotEmpty()) { "The authoritative review diff contains no parseable changed hunks." }
  }

  fun ownedFiles(paths: Set<String>): List<ReviewChangedFileEvidence> = files.filter { it.path in paths }

  companion object {
    fun parseAttributable(diff: String): ReviewDiffEvidence? = parseAttributableReviewDiffEvidence(diff)

    fun parse(diff: String): ReviewDiffEvidence =
      when (val result = parseOrRejection(diff)) {
        is ReviewDiffEvidenceParseResult.Parsed -> result.evidence
        is ReviewDiffEvidenceParseResult.Rejected -> throw IllegalArgumentException(result.reason)
      }

    fun parseOrRejection(diff: String): ReviewDiffEvidenceParseResult = parseReviewDiffEvidenceOrRejection(diff)
  }
}

sealed interface ReviewDiffEvidenceParseResult {
  data class Parsed(val evidence: ReviewDiffEvidence) : ReviewDiffEvidenceParseResult

  data class Rejected(val reason: String) : ReviewDiffEvidenceParseResult
}

data class ReviewChangedFileEvidence(
  val path: String,
  val changedContent: String,
  val fullRecord: String,
  val oldPath: String? = path,
  val newPath: String? = path,
)
