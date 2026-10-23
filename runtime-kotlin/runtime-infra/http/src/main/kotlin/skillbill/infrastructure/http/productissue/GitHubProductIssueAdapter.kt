package skillbill.infrastructure.http.productissue

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.http.HttpHeaders
import skillbill.infrastructure.http.JSON_CONTENT_TYPE
import skillbill.infrastructure.http.SKILL_BILL_PRODUCT_REPOSITORY_API
import skillbill.infrastructure.http.SKILL_BILL_PRODUCT_REPOSITORY_ID
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.Comment
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.CommentTarget
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.FORBIDDEN
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.GITHUB_API
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.GITHUB_API_VERSION
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.GITHUB_API_VERSION_HEADER
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.GITHUB_JSON_MEDIA_TYPE
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.ISSUES_URL
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.InvalidPage
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.Lookup
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.LookupFailure
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.NOT_FOUND
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.NextPage
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.NoNextPage
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.PAGE_SIZE
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.RATE_LIMIT_REMAINING
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.RATE_LIMIT_RESET
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.REPORT_MARKER_PREFIX
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.REPORT_MARKER_SUFFIX
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.RETRY_AFTER
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.Repository
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.SEARCH_PAGE_LIMIT
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.SERVER_ERROR_MIN
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.SUCCESS_RANGE
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.TOO_MANY_REQUESTS
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.UNAUTHORIZED
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.Value
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.array
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.comment
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.decodeComment
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.failed
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.incidentMarker
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.issue
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.issueApiUrl
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.issueEntry
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.json
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.nextPage
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.objectValue
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.pageUrl
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.positiveLong
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.searchPage
import skillbill.infrastructure.http.productissue.GitHubProductIssueCodec.validFullName
import skillbill.ports.blockincident.ProductIssueTrackerPort
import skillbill.ports.blockincident.model.ProductIssueResult
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import java.io.IOException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Inject
class GitHubProductIssueAdapter(
  private val transport: RemoteTransportPort,
  private val clock: Clock,
) : ProductIssueTrackerPort {
  override fun findByFingerprint(
    fingerprint: String,
    token: String,
  ): ProductIssueResult {
    val repository =
      when (val result = repository(token)) {
        is Value -> result.value
        is LookupFailure -> return result.result
      }
    val marker = "$REPORT_MARKER_PREFIX$fingerprint$REPORT_MARKER_SUFFIX"
    val query = "\"skill-bill-report-fingerprint:$fingerprint\" repo:${repository.fullName} is:issue in:body"
    when (val result = search(marker, query, repository, token)) {
      is Value -> result.value?.let { return it }
      is LookupFailure -> return result.result
    }
    return listIssues(marker, repository, token)
  }

  override fun create(
    title: String,
    body: String,
    labels: List<String>,
    token: String,
  ): ProductIssueResult {
    val response =
      when (
        val result =
          request(
            "POST",
            ISSUES_URL,
            json(
              mapOf(
                ProductIssueHttpKeys.TITLE to title,
                ProductIssueHttpKeys.BODY to body,
                ProductIssueHttpKeys.LABELS to labels,
              ),
            ),
            token,
            write = true,
          )
      ) {
        is Response -> result.value
        is CallFailure -> return result.result
      }
    return when (val issue = issue(response.body, repositoryName = null, requireBody = false)) {
      is Value -> ProductIssueResult.Created(issue.value.number, issue.value.url)
      is LookupFailure -> ProductIssueResult.Ambiguous
    }
  }

  override fun comment(
    issueNumber: Long,
    body: String,
    token: String,
  ): ProductIssueResult {
    if (issueNumber <= 0) return failed()
    val marker = incidentMarker(body) ?: return failed()
    val repository =
      when (val result = repository(token)) {
        is Value -> result.value
        is LookupFailure -> return result.result
      }
    val target = CommentTarget(issueNumber, body, marker, repository.fullName)
    var matched: Comment? = null
    val scanned =
      scanPages("$ISSUES_URL/$issueNumber/comments", emptyMap(), token) { response ->
        when (val decoded = matchingComment(response.body, target, matched)) {
          is Value -> {
            matched = decoded.value
            Value(null)
          }
          is LookupFailure -> LookupFailure(decoded.result)
        }
      }
    return when (scanned) {
      is LookupFailure -> scanned.result
      is Value ->
        when (val existing = matched) {
          null -> postComment(target, token)
          else ->
            if (existing.body == body) {
              ProductIssueResult.Found(issueNumber, issueApiUrl(issueNumber))
            } else {
              patchComment(target, existing, token)
            }
        }
    }
  }

  private fun matchingComment(
    body: String,
    target: CommentTarget,
    previous: Comment?,
  ): Lookup<Comment?> {
    val entries =
      when (val decoded = array(body)) {
        is Value -> decoded.value
        is LookupFailure -> return decoded
      }
    var matched = previous
    for (entry in entries) {
      val comment =
        when (val decoded = comment(entry, target.issueNumber, target.repositoryName)) {
          is Value -> decoded.value
          is LookupFailure -> return decoded
        }
      if (comment.body.contains(target.marker)) {
        if (matched != null) return LookupFailure(ProductIssueResult.Ambiguous)
        matched = comment
      }
    }
    return Value(matched)
  }

  private fun search(
    marker: String,
    query: String,
    repository: Repository,
    token: String,
  ): Lookup<ProductIssueResult?> =
    scanPages(
      "$GITHUB_API/search/issues",
      mapOf(ProductIssueHttpKeys.QUERY to query),
      token,
      pageLimit = SEARCH_PAGE_LIMIT,
    ) { response ->
      when (val decoded = searchPage(response.body, repository.fullName)) {
        is Value ->
          Value(
            decoded.value.issues.firstOrNull { !it.pullRequest && it.body?.contains(marker) == true }
              ?.let { ProductIssueResult.Found(it.number, it.url, it.originReportId) },
          )
        is LookupFailure -> decoded
      }
    }

  private fun listIssues(
    marker: String,
    repository: Repository,
    token: String,
  ): ProductIssueResult =
    when (
      val result =
        scanPages(ISSUES_URL, mapOf(ProductIssueHttpKeys.STATE to "all"), token) { response ->
          matchingIssue(response.body, marker, repository.fullName)
        }
    ) {
      is Value -> result.value ?: ProductIssueResult.NotFound
      is LookupFailure -> result.result
    }

  private fun matchingIssue(
    body: String,
    marker: String,
    repositoryName: String,
  ): Lookup<ProductIssueResult?> {
    val entries =
      when (val decoded = array(body)) {
        is Value -> decoded.value
        is LookupFailure -> return decoded
      }
    for (entry in entries) {
      val issue =
        when (val decoded = issueEntry(entry, repositoryName)) {
          is Value -> decoded.value
          is LookupFailure -> return decoded
        }
      if (!issue.pullRequest && issue.body?.contains(marker) == true) {
        return Value(ProductIssueResult.Found(issue.number, issue.url, issue.originReportId))
      }
    }
    return Value(null)
  }

  private fun scanPages(
    endpoint: String,
    query: Map<String, String>,
    token: String,
    pageLimit: Int = Int.MAX_VALUE,
    decode: (RemoteTransportResponse) -> Lookup<ProductIssueResult?>,
  ): Lookup<ProductIssueResult?> {
    var nextNumber: Int? = 1
    val visited = mutableSetOf<Int>()
    val fixedQuery = query + (ProductIssueHttpKeys.PER_PAGE to PAGE_SIZE.toString())
    var terminal: Lookup<ProductIssueResult?> = LookupFailure(failed())
    while (nextNumber != null) {
      val page = nextNumber
      visited.add(page)
      val response =
        when (val result = request("GET", pageUrl(endpoint, fixedQuery, page), null, token)) {
          is Response -> result.value
          is CallFailure -> return LookupFailure(result.result)
        }
      val decoded = decode(response)
      if (decoded !is Value || decoded.value != null) return decoded
      val advance = boundedAdvance(nextPage(response, endpoint, fixedQuery, page), page, pageLimit, visited)
      nextNumber = (advance as? NextPage)?.page
      if (advance == NoNextPage) terminal = Value(null)
    }
    return terminal
  }

  private fun boundedAdvance(
    advance: GitHubProductIssueCodec.PageAdvance,
    page: Int,
    limit: Int,
    visited: Set<Int>,
  ): GitHubProductIssueCodec.PageAdvance =
    when {
      advance !is NextPage -> advance
      advance.page in visited -> InvalidPage
      page >= limit || advance.page > limit -> NoNextPage
      else -> advance
    }

  private fun repository(token: String): Lookup<Repository> {
    val response =
      when (val result = request("GET", SKILL_BILL_PRODUCT_REPOSITORY_API, null, token)) {
        is Response -> result.value
        is CallFailure -> return LookupFailure(result.result)
      }
    val map =
      when (val result = objectValue(response.body)) {
        is Value -> result.value
        is LookupFailure -> return LookupFailure(result.result)
      }
    val id = positiveLong(map[ProductIssueHttpKeys.ID])
    val name = map[ProductIssueHttpKeys.FULL_NAME] as? String
    if (id != SKILL_BILL_PRODUCT_REPOSITORY_ID || name == null || !validFullName(name)) return LookupFailure(failed())
    return Value(Repository(name))
  }

  private fun postComment(
    target: CommentTarget,
    token: String,
  ): ProductIssueResult {
    val response =
      when (
        val result =
          request(
            "POST",
            "$ISSUES_URL/${target.issueNumber}/comments",
            json(mapOf(ProductIssueHttpKeys.BODY to target.body)),
            token,
            write = true,
          )
      ) {
        is Response -> result.value
        is CallFailure -> return result.result
      }
    val decoded = decodedCommentResponse(response.body, target)
    return decoded?.let { ProductIssueResult.Created(target.issueNumber, it.url) } ?: ProductIssueResult.Ambiguous
  }

  private fun patchComment(
    target: CommentTarget,
    existing: Comment,
    token: String,
  ): ProductIssueResult {
    val response =
      when (
        val result =
          request(
            "PATCH",
            "$GITHUB_API/repos/${target.repositoryName}/issues/comments/${existing.id}",
            json(mapOf(ProductIssueHttpKeys.BODY to target.body)),
            token,
            write = true,
          )
      ) {
        is Response -> result.value
        is CallFailure -> return result.result
      }
    val decoded = decodedCommentResponse(response.body, target)
    return if (decoded?.id == existing.id && decoded.url == existing.url) {
      ProductIssueResult.Found(target.issueNumber, issueApiUrl(target.issueNumber))
    } else {
      ProductIssueResult.Ambiguous
    }
  }

  private fun decodedCommentResponse(
    body: String,
    target: CommentTarget,
  ): Comment? {
    val map =
      when (val decoded = objectValue(body)) {
        is Value -> decoded.value
        is LookupFailure -> return null
      }
    return decodeComment(map, target.issueNumber, target.repositoryName)
      ?.takeIf { it.body == target.body && it.body.contains(target.marker) }
  }

  private fun request(
    method: String,
    url: String,
    body: String?,
    token: String,
    write: Boolean = false,
  ): Call {
    val response =
      try {
        transport.execute(method, url, body, headers(token, write))
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
      } catch (_: HttpConnectTimeoutException) {
        return CallFailure(ProductIssueResult.Failed(ProductReportFailure.OFFLINE))
      } catch (_: HttpTimeoutException) {
        return CallFailure(
          if (write) ProductIssueResult.Ambiguous else ProductIssueResult.Failed(ProductReportFailure.OFFLINE),
        )
      } catch (_: IOException) {
        return CallFailure(
          if (write) ProductIssueResult.Ambiguous else ProductIssueResult.Failed(ProductReportFailure.OFFLINE),
        )
      }
    return when {
      write && response.statusCode >= SERVER_ERROR_MIN -> CallFailure(ProductIssueResult.Ambiguous)
      response.statusCode in SUCCESS_RANGE -> Response(response)
      else -> CallFailure(response.failure())
    }
  }

  private fun headers(
    token: String,
    write: Boolean,
  ): Map<String, String> =
    buildMap {
      put(HttpHeaders.AUTHORIZATION, "Bearer $token")
      put(HttpHeaders.ACCEPT, GITHUB_JSON_MEDIA_TYPE)
      put(GITHUB_API_VERSION_HEADER, GITHUB_API_VERSION)
      if (write) put(HttpHeaders.CONTENT_TYPE, JSON_CONTENT_TYPE)
    }

  private fun RemoteTransportResponse.failure(): ProductIssueResult.Failed {
    val rateLimited = statusCode == TOO_MANY_REQUESTS || statusCode == FORBIDDEN && isRateLimited()
    return when {
      statusCode == UNAUTHORIZED || statusCode == NOT_FOUND ->
        ProductIssueResult.Failed(ProductReportFailure.PERMISSION_REFUSED)
      rateLimited -> ProductIssueResult.Failed(ProductReportFailure.RATE_LIMITED, retryAt())
      statusCode == FORBIDDEN -> ProductIssueResult.Failed(ProductReportFailure.PERMISSION_REFUSED)
      statusCode >= SERVER_ERROR_MIN -> ProductIssueResult.Failed(ProductReportFailure.SUBMISSION_FAILED)
      else -> ProductIssueResult.Failed(ProductReportFailure.SUBMISSION_FAILED)
    }
  }

  private fun RemoteTransportResponse.isRateLimited(): Boolean =
    header(RATE_LIMIT_REMAINING)?.toLongOrNull() == 0L || header(RETRY_AFTER) != null

  private fun RemoteTransportResponse.retryAt(): Instant? {
    val now = clock.instant()
    val reset = header(RATE_LIMIT_RESET)?.toLongOrNull()?.takeIf { it >= 0 }?.let(::safeEpochSeconds)
    val retry =
      header(RETRY_AFTER)?.let { value ->
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { safePlusSeconds(now, it) } ?: safeHttpDate(value)
      }
    return listOfNotNull(reset, retry).maxOrNull()
  }

  private fun RemoteTransportResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf(String::isNotEmpty)

  private fun safeEpochSeconds(seconds: Long): Instant? =
    try {
      Instant.ofEpochSecond(seconds)
    } catch (_: DateTimeException) {
      null
    }

  private fun safePlusSeconds(
    base: Instant,
    seconds: Long,
  ): Instant? =
    try {
      base.plusSeconds(seconds)
    } catch (_: DateTimeException) {
      null
    } catch (_: ArithmeticException) {
      null
    }

  private fun safeHttpDate(value: String): Instant? =
    try {
      Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value))
    } catch (_: DateTimeParseException) {
      null
    } catch (_: DateTimeException) {
      null
    }

  private sealed interface Call

  private data class Response(val value: RemoteTransportResponse) : Call

  private data class CallFailure(val result: ProductIssueResult) : Call
}

internal object ProductIssueHttpKeys {
  const val BODY = "body"
  const val FULL_NAME = "full_name"
  const val ID = "id"
  const val HTML_URL = "html_url"
  const val NUMBER = "number"
  const val PULL_REQUEST = "pull_request"
  const val REPOSITORY_URL = "repository_url"
  const val ITEMS = "items"
  const val TOTAL_COUNT = "total_count"
  const val INCOMPLETE_RESULTS = "incomplete_results"
  const val TITLE = "title"
  const val LABELS = "labels"
  const val QUERY = "q"
  const val PAGE = "page"
  const val PER_PAGE = "per_page"
  const val STATE = "state"
}
