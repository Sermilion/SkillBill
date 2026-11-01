package skillbill.review.model

import skillbill.error.shellcontent.invalidReviewContextSchemaError
import java.time.Instant

enum class ReviewFindingActionKind(val wireValue: String) {
  FIX("fix"),
  VERIFY("verify"),
  ;

  companion object {
    fun fromWire(value: String): ReviewFindingActionKind =
      entries.firstOrNull { it.wireValue == value }
        ?: throw invalidReviewContextSchemaError("finding_action", "Unknown finding action '$value'.")
  }
}

data class ReviewFindingAction(
  val id: Long,
  val kind: ReviewFindingActionKind,
  val startedAt: Instant,
  val finishedAt: Instant? = null,
  val completed: Boolean = false,
  val fixed: Int = 0,
  val open: Int = 0,
  val other: Int = 0,
)
