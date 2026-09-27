package skillbill.ports.review.pullrequest.model

data class ReviewPullRequest(
  val number: Int,
  val url: String,
  val owner: String,
  val name: String,
  val headRefName: String,
  val baseRefName: String,
  val headOid: String,
)

sealed interface ReviewPullRequestResolution {
  data class Found(val pullRequest: ReviewPullRequest) : ReviewPullRequestResolution

  data object Absent : ReviewPullRequestResolution

  data class Unavailable(val reason: String) : ReviewPullRequestResolution
}
