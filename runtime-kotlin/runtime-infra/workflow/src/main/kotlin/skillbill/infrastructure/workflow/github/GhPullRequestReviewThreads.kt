package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewPullRequestResolution
import skillbill.ports.review.pullrequest.model.ReviewThread
import skillbill.ports.review.pullrequest.model.ReviewThreadComment
import skillbill.ports.review.pullrequest.model.ReviewThreadListing
import skillbill.ports.review.pullrequest.model.ReviewThreadReplyResult
import java.nio.file.Path

class GhPullRequestReviewThreads internal constructor(
  private val gh: GhCommandRunner,
) : PullRequestReviewThreadOperations {
  @Inject
  constructor() : this(ProcessGhCommandRunner(maxOutputBytes = THREAD_PAGE_OUTPUT_BYTES))

  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun resolvePullRequest(
    repoRoot: Path,
    reference: String?,
  ): ReviewPullRequestResolution {
    val result = gh.run(repoRoot, listOf("pr", "view") + listOfNotNull(reference) + listOf("--json", PR_FIELDS))
    if (result.exitCode != 0) {
      return if (NO_PULL_REQUEST.containsMatchIn(result.stdout)) {
        ReviewPullRequestResolution.Absent
      } else {
        ReviewPullRequestResolution.Unavailable(result.describeFailure())
      }
    }
    return readJson(result.stdout)?.let(::pullRequestFrom)?.let(ReviewPullRequestResolution::Found)
      ?: ReviewPullRequestResolution.Unavailable("GitHub CLI returned unparsable pull request output.")
  }

  override fun reviewThreads(
    repoRoot: Path,
    pullRequest: ReviewPullRequest,
  ): ReviewThreadListing {
    val threads = mutableListOf<ReviewThread>()
    var cursor: String? = null
    repeat(MAX_THREAD_PAGES) {
      val page =
        when (val read = threadPage(repoRoot, pullRequest, cursor)) {
          is ThreadPage.Unavailable -> return ReviewThreadListing.Unavailable(read.reason)
          is ThreadPage.Read -> read
        }
      threads += page.threads
      if (page.endCursor == null) return ReviewThreadListing.Ok(threads)
      cursor = page.endCursor
    }
    return ReviewThreadListing.Unavailable("Pull request #${pullRequest.number} has more than $MAX_THREAD_PAGES pages.")
  }

  override fun replyToThread(
    repoRoot: Path,
    threadId: String,
    body: String,
  ): ReviewThreadReplyResult {
    val result =
      gh.run(
        repoRoot,
        listOf("api", "graphql", "-f", "threadId=$threadId", "-f", "body=$body", "-f", "query=$REPLY_MUTATION"),
      )
    if (result.exitCode != 0) return ReviewThreadReplyResult.Failed(result.describeFailure())
    val url =
      readJson(result.stdout)
        ?.takeIf { root -> root.path("errors").isMissingNode }
        ?.path("data")?.path("addPullRequestReviewThreadReply")?.path("comment")?.path("url")
        ?.takeIf(JsonNode::isTextual)?.asText()
    return url?.let(ReviewThreadReplyResult::Posted)
      ?: ReviewThreadReplyResult.Failed(
        "GitHub returned no reply comment for thread $threadId: " +
          result.describeFailure().take(FAILURE_EXCERPT_CHARS),
      )
  }

  private fun threadPage(
    repoRoot: Path,
    pullRequest: ReviewPullRequest,
    cursor: String?,
  ): ThreadPage {
    val args =
      listOf(
        "api",
        "graphql",
        "-f",
        "owner=${pullRequest.owner}",
        "-f",
        "repo=${pullRequest.name}",
        "-F",
        "number=${pullRequest.number}",
      ) + cursor?.let { listOf("-f", "cursor=$it") }.orEmpty() + listOf("-f", "query=$THREADS_QUERY")
    val result = gh.run(repoRoot, args)
    if (result.exitCode != 0) return ThreadPage.Unavailable(result.describeFailure())
    return runCatching { parseThreadPage(result.stdout, cursor) }
      .getOrElse { error -> ThreadPage.Unavailable("GitHub returned unreadable review threads (${error.message}).") }
  }

  private fun parseThreadPage(
    stdout: String,
    cursor: String?,
  ): ThreadPage.Read {
    val root = checkNotNull(readJson(stdout)) { "not a JSON object" }
    check(root.path("errors").isMissingNode) {
      "GraphQL errors: ${root.path("errors").toString().take(FAILURE_EXCERPT_CHARS)}"
    }
    val connection = root.path("data").path("repository").path("pullRequest").path("reviewThreads")
    check(connection.path("nodes").isArray) { "no reviewThreads nodes" }
    val threads = connection.path("nodes").map(::threadFrom)
    val pageInfo = connection.path("pageInfo")
    if (!pageInfo.path("hasNextPage").asBoolean(false)) return ThreadPage.Read(threads, endCursor = null)
    val endCursor = pageInfo.textOrNull("endCursor")
    check(endCursor != null && endCursor != cursor) { "more review threads reported but no next-page cursor" }
    return ThreadPage.Read(threads, endCursor)
  }

  private fun threadFrom(node: JsonNode): ReviewThread =
    ReviewThread(
      id = node.requiredText("id"),
      isResolved = node.requiredBoolean("isResolved"),
      isOutdated = node.requiredBoolean("isOutdated"),
      path = node.requiredText("path"),
      line = node.path("line").takeIf(JsonNode::isInt)?.asInt(),
      originalLine = node.path("originalLine").takeIf(JsonNode::isInt)?.asInt(),
      comments =
        node.path("comments").path("nodes").map { comment ->
          ReviewThreadComment(
            id = comment.textOrNull("id").orEmpty(),
            author = comment.path("author").textOrNull("login") ?: "ghost",
            body = comment.textOrNull("body").orEmpty(),
            url = comment.textOrNull("url").orEmpty(),
            createdAt = comment.textOrNull("createdAt").orEmpty(),
          )
        },
    )

  private fun pullRequestFrom(node: JsonNode): ReviewPullRequest? =
    runCatching {
      val url = node.requiredText("url")
      val (owner, name) = checkNotNull(PULL_REQUEST_URL.find(url)) { "unrecognised pull request url" }.destructured
      ReviewPullRequest(
        number = node.path("number").also { number -> check(number.isInt) { "no number" } }.asInt(),
        url = url,
        owner = owner,
        name = name,
        headRefName = node.requiredText("headRefName"),
        baseRefName = node.requiredText("baseRefName"),
        headOid = node.requiredText("headRefOid"),
      )
    }.getOrNull()

  private fun readJson(stdout: String): JsonNode? =
    runCatching { mapper.readTree(stdout) }.getOrNull()?.takeIf(JsonNode::isObject)

  private sealed interface ThreadPage {
    data class Read(
      val threads: List<ReviewThread>,
      val endCursor: String?,
    ) : ThreadPage

    data class Unavailable(val reason: String) : ThreadPage
  }
}

private fun JsonNode.textOrNull(field: String): String? =
  path(field).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)

private fun JsonNode.requiredText(field: String): String = checkNotNull(textOrNull(field)) { "missing $field" }

private fun JsonNode.requiredBoolean(field: String): Boolean =
  path(field).also { value -> check(value.isBoolean) { "missing $field" } }.asBoolean()

private const val PR_FIELDS = "number,url,headRefName,baseRefName,headRefOid"
private val NO_PULL_REQUEST = Regex("(?i)no (open )?pull requests? found")
private val PULL_REQUEST_URL = Regex("""^https?://[^/]+/([^/]+)/([^/]+)/pull/\d+""")

private const val THREADS_QUERY =
  "query(\$owner:String!,\$repo:String!,\$number:Int!,\$cursor:String){" +
    "repository(owner:\$owner,name:\$repo){pullRequest(number:\$number){" +
    "reviewThreads(first:50,after:\$cursor){pageInfo{hasNextPage endCursor}" +
    "nodes{id isResolved isOutdated path line originalLine " +
    "comments(first:100){nodes{id author{login} body createdAt url}}}}}}}"

private const val REPLY_MUTATION =
  "mutation(\$threadId:ID!,\$body:String!){" +
    "addPullRequestReviewThreadReply(input:{pullRequestReviewThreadId:\$threadId,body:\$body}){comment{id url}}}"

private const val MAX_THREAD_PAGES = 40
private const val THREAD_PAGE_OUTPUT_BYTES: Long = 4L * 1024 * 1024
private const val FAILURE_EXCERPT_CHARS = 400
