package skillbill.application.review.diffcomment.model

import skillbill.review.diffcomment.model.DiffCommentSide
import skillbill.review.diffcomment.model.DiffCommentThread

sealed interface DiffCommentListResult {
  data class Listed(
    val repositoryIdentity: String,
    val branch: String,
    val threads: List<DiffCommentThread>,
  ) : DiffCommentListResult

  data class Unavailable(val reason: String) : DiffCommentListResult
}

sealed interface DiffCommentThreadResult {
  data class Saved(val thread: DiffCommentThread, val changed: Boolean) : DiffCommentThreadResult

  data object NotFound : DiffCommentThreadResult

  data class Rejected(val rejection: DiffCommentRejection) : DiffCommentThreadResult

  data class Unavailable(val reason: String) : DiffCommentThreadResult
}

sealed interface DiffCommentCountResult {
  data class Counted(val count: Int) : DiffCommentCountResult

  data class Unavailable(val reason: String) : DiffCommentCountResult
}

sealed interface DiffCommentDeleteResult {
  data class Deleted(val threadRemoved: Boolean) : DiffCommentDeleteResult

  data object NotFound : DiffCommentDeleteResult
}

enum class DiffCommentRejection(val wireValue: String) {
  BLANK_BODY("blank_body"),
  BODY_TOO_LARGE("body_too_large"),
  INVALID_PATH("invalid_path"),
  INVALID_SEVERITY("invalid_severity"),
  NOT_EDITABLE("not_editable"),
  PENDING_THREAD("pending_thread"),
  INVALID_TRANSITION("invalid_transition"),
  OUTCOME_NOT_ALLOWED("outcome_not_allowed"),
}

data class DiffCommentAgentThreadRequest(
  val path: String?,
  val side: DiffCommentSide?,
  val line: Int?,
  val body: String,
  val agentId: String? = null,
  val sourceRef: String? = null,
  val sourceLabel: String? = null,
  val severity: String? = null,
  val findingRef: String? = null,
)
