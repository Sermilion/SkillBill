package skillbill.ports.review.pullrequest

import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewPullRequestResolution
import skillbill.ports.review.pullrequest.model.ReviewThreadListing
import skillbill.ports.review.pullrequest.model.ReviewThreadReplyResult
import java.nio.file.Path

/**
 * GitHub pull-request review threads, read with their resolved and outdated flags. There is deliberately no resolve
 * member: resolving a thread is the reviewer's call.
 */
interface PullRequestReviewThreadOperations {
  /** Resolves [reference] (a PR number or URL), or the current branch's pull request when it is null. */
  fun resolvePullRequest(
    repoRoot: Path,
    reference: String?,
  ): ReviewPullRequestResolution

  /** Every review thread of [pullRequest], across all pages; a partial listing is [ReviewThreadListing.Unavailable]. */
  fun reviewThreads(
    repoRoot: Path,
    pullRequest: ReviewPullRequest,
  ): ReviewThreadListing

  fun replyToThread(
    repoRoot: Path,
    threadId: String,
    body: String,
  ): ReviewThreadReplyResult
}
