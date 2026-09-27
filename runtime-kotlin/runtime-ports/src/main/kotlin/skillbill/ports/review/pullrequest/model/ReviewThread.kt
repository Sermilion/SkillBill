package skillbill.ports.review.pullrequest.model

/** One review thread as GitHub's GraphQL API reports it; the flags never come from the flat comments list. */
data class ReviewThread(
  val id: String,
  val isResolved: Boolean,
  val isOutdated: Boolean,
  val path: String,
  val line: Int?,
  val originalLine: Int?,
  val comments: List<ReviewThreadComment>,
)

data class ReviewThreadComment(
  val id: String,
  val author: String,
  val body: String,
  val url: String,
  val createdAt: String,
)

sealed interface ReviewThreadListing {
  data class Ok(val threads: List<ReviewThread>) : ReviewThreadListing

  data class Unavailable(val reason: String) : ReviewThreadListing
}

sealed interface ReviewThreadReplyResult {
  data class Posted(val commentUrl: String) : ReviewThreadReplyResult

  data class Failed(val reason: String) : ReviewThreadReplyResult
}
