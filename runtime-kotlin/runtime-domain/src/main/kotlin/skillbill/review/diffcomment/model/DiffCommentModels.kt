package skillbill.review.diffcomment.model

import skillbill.review.model.ParallelReviewSeverity
import java.time.Instant
import java.util.Locale

enum class DiffCommentThreadState(val wireValue: String) {
  PENDING("pending"),
  OPEN("open"),
  RESOLVED("resolved"),
  DECLINED("declined"),
  ;

  companion object {
    fun fromWire(value: String): DiffCommentThreadState? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class DiffCommentAuthor(val wireValue: String) {
  USER("user"),
  AGENT("agent"),
  ;

  companion object {
    fun fromWire(value: String): DiffCommentAuthor? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class DiffCommentSide(val wireValue: String) {
  OLD("old"),
  NEW("new"),
  ;

  companion object {
    fun fromWire(value: String): DiffCommentSide? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class DiffCommentOutcome(val wireValue: String) {
  FIXED("fixed"),
  REFUTED("refuted"),
  SKIPPED("skipped"),
  FAILED("failed"),
  ;

  companion object {
    fun fromWire(value: String): DiffCommentOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class DiffComment(
  val id: String,
  val threadId: String,
  val position: Int,
  val author: DiffCommentAuthor,
  val agentId: String?,
  val body: String,
  val outcome: DiffCommentOutcome?,
  val createdAt: Instant,
  val editedAt: Instant?,
) {
  init {
    require(body.isNotBlank())
    require(body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
  }

  companion object {
    const val MAX_BODY_BYTES = 16384
  }
}

data class DiffCommentThread(
  val id: String,
  val repositoryIdentity: String,
  val headBranch: String,
  val path: String?,
  val side: DiffCommentSide?,
  val line: Int?,
  val anchorCommitSha: String,
  val anchorLineText: String?,
  val state: DiffCommentThreadState,
  val sourceRef: String?,
  val sourceLabel: String?,
  val severity: String?,
  val findingRef: String?,
  val createdAt: Instant,
  val updatedAt: Instant,
  val comments: List<DiffComment>,
  val outdated: Boolean,
) {
  init {
    require(line == null || path != null)
    require((side == null) == (line == null))
    require(line == null || line >= 1)
    require(acceptsSeverity(severity))
  }

  companion object {
    fun acceptsSeverity(severity: String?): Boolean =
      severity == null || ParallelReviewSeverity.entries.any { it.displayName.lowercase(Locale.ROOT) == severity }
  }
}
