package skillbill.infrastructure.http.productissue

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.blockincident.BlockIncidentReportKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.http.SKILL_BILL_PRODUCT_REPOSITORY_API
import skillbill.ports.blockincident.model.ProductIssueResult
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.workflow.taskruntime.model.blockincident.report.ProductReportFailure
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

internal object GitHubProductIssueCodec {
  fun searchPage(
    body: String,
    repositoryName: String,
  ): Lookup<SearchPage> {
    val map =
      when (val result = objectValue(body)) {
        is Value -> result.value
        is LookupFailure -> return LookupFailure(result.result)
      }
    val total = nonNegativeLong(map[ProductIssueHttpKeys.TOTAL_COUNT])
    val entries = map[ProductIssueHttpKeys.ITEMS] as? List<*>
    if (total == null || entries == null || map[ProductIssueHttpKeys.INCOMPLETE_RESULTS] !is Boolean) {
      return LookupFailure(failed())
    }
    val issues = decodedSearchIssues(entries, repositoryName) ?: return LookupFailure(failed())
    return if (total < issues.size) LookupFailure(failed()) else Value(SearchPage(issues))
  }

  private fun decodedSearchIssues(
    entries: List<*>,
    repositoryName: String,
  ): List<Issue>? =
    entries.map { raw ->
      when (val result = searchIssue(raw, repositoryName)) {
        is Value -> result.value
        is LookupFailure -> return null
      }
    }

  fun searchIssue(
    raw: Any?,
    repositoryName: String,
  ): Lookup<Issue> {
    val map = mapValue(raw) ?: return LookupFailure(failed())
    if (ProductIssueHttpKeys.PULL_REQUEST in map) {
      return if (map[ProductIssueHttpKeys.PULL_REQUEST] is Map<*, *>) {
        Value(Issue(0, "", null, pullRequest = true))
      } else {
        LookupFailure(failed())
      }
    }
    val repositoryUrl = map[ProductIssueHttpKeys.REPOSITORY_URL] as? String
    return if (repositoryUrl != null && validRepositoryApiUrl(repositoryUrl, repositoryName)) {
      issueMap(map, repositoryName, requireBody = true)
    } else {
      LookupFailure(failed())
    }
  }

  fun issueEntry(
    raw: Any?,
    repositoryName: String,
  ): Lookup<Issue> {
    val map = mapValue(raw) ?: return LookupFailure(failed())
    if (ProductIssueHttpKeys.PULL_REQUEST in map) {
      return if (map[ProductIssueHttpKeys.PULL_REQUEST] is Map<*, *>) {
        Value(Issue(0, "", null, pullRequest = true))
      } else {
        LookupFailure(failed())
      }
    }
    return issueMap(map, repositoryName, requireBody = true)
  }

  fun issue(
    body: String,
    repositoryName: String?,
    requireBody: Boolean,
  ): Lookup<Issue> {
    val map =
      when (val result = objectValue(body)) {
        is Value -> result.value
        is LookupFailure -> return LookupFailure(result.result)
      }
    return issueMap(map, repositoryName, requireBody)
  }

  fun issueMap(
    map: Map<String, Any?>,
    repositoryName: String?,
    requireBody: Boolean,
  ): Lookup<Issue> {
    val number = positiveLong(map[ProductIssueHttpKeys.NUMBER]) ?: return LookupFailure(failed())
    val url = map[ProductIssueHttpKeys.HTML_URL] as? String ?: return LookupFailure(failed())
    val rawBody = map[ProductIssueHttpKeys.BODY]
    val validBody = !requireBody || (ProductIssueHttpKeys.BODY in map && (rawBody == null || rawBody is String))
    if (!validIssueUrl(url, number, repositoryName) || !validBody) return LookupFailure(failed())
    val body = rawBody as? String
    val origin =
      body?.let { content ->
        REPORT_ORIGIN.findAll(content).map { it.groupValues[1] }.toList().singleOrNull()
      }
    return Value(Issue(number, url, body, ProductIssueHttpKeys.PULL_REQUEST in map, origin))
  }

  fun comment(
    raw: Any?,
    issueNumber: Long,
    repositoryName: String,
  ): Lookup<Comment> {
    val map = mapValue(raw) ?: return LookupFailure(failed())
    return decodeComment(map, issueNumber, repositoryName)?.let(::Value) ?: LookupFailure(failed())
  }

  fun decodeComment(
    map: Map<String, Any?>,
    issueNumber: Long,
    repositoryName: String,
  ): Comment? {
    val id = positiveLong(map[ProductIssueHttpKeys.ID]) ?: return null
    val url = map[ProductIssueHttpKeys.HTML_URL] as? String ?: return null
    val body = map[ProductIssueHttpKeys.BODY] as? String ?: return null
    return if (validCommentUrl(url, issueNumber, id, repositoryName)) Comment(id, url, body) else null
  }

  fun array(body: String): Lookup<List<Any?>> =
    try {
      Value(JsonCodec.parseJsonArrayStrict(body))
    } catch (_: SkillBillRuntimeException) {
      LookupFailure(failed())
    }

  fun objectValue(body: String): Lookup<Map<String, Any?>> =
    try {
      JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(body))?.let(::Value) ?: LookupFailure(failed())
    } catch (_: SkillBillRuntimeException) {
      LookupFailure(failed())
    }

  fun mapValue(value: Any?): Map<String, Any?>? =
    try {
      JsonCodec.anyToStringAnyMap(value)
    } catch (_: SkillBillRuntimeException) {
      null
    }

  fun nextPage(
    response: RemoteTransportResponse,
    endpoint: String,
    fixedQuery: Map<String, String>,
    currentPage: Int,
  ): PageAdvance {
    val link = response.headers.entries.firstOrNull { it.key.equals(LINK_HEADER, true) }?.value ?: return NoNextPage
    val nextUrl =
      when (val decoded = nextLink(link)) {
        is Value -> decoded.value ?: return NoNextPage
        is LookupFailure -> return InvalidPage
      }
    return validateNextPage(nextUrl, endpoint, fixedQuery, currentPage)
  }

  private fun nextLink(link: String): Lookup<String?> {
    val entries =
      link.split(',').map { value ->
        val target = LINK_TARGET.find(value)?.groupValues?.get(1) ?: return LookupFailure(failed())
        val relation = LINK_RELATION.find(value)?.groupValues?.get(1) ?: return LookupFailure(failed())
        target to relation
      }
    return Value(entries.firstOrNull { it.second.equals("next", ignoreCase = true) }?.first)
  }

  private fun validateNextPage(
    nextUrl: String,
    endpoint: String,
    fixedQuery: Map<String, String>,
    currentPage: Int,
  ): PageAdvance {
    val uri =
      try {
        URI(nextUrl)
      } catch (_: IllegalArgumentException) {
        return InvalidPage
      }
    val expected = URI(endpoint)
    if (!validAuthority(uri, API_HOST) || uri.path != expected.path) return InvalidPage
    val values = queryValues(uri.rawQuery) ?: return InvalidPage
    val next = values[ProductIssueHttpKeys.PAGE]?.singleOrNull()?.toIntOrNull()
    return if (queryMatches(values, fixedQuery) && next != null && next > currentPage) NextPage(next) else InvalidPage
  }

  private fun queryMatches(
    values: Map<String, List<String>>,
    fixedQuery: Map<String, String>,
  ): Boolean {
    val allowed = fixedQuery.keys + ProductIssueHttpKeys.PAGE
    return fixedQuery.all { (key, value) -> values[key]?.singleOrNull() == value } && values.keys.all { it in allowed }
  }

  private fun validAuthority(
    uri: URI,
    host: String,
  ): Boolean = uri.scheme == "https" && uri.host == host && uri.rawUserInfo == null && uri.port == -1

  fun queryValues(query: String?): Map<String, List<String>>? {
    if (query == null) return null
    val values = linkedMapOf<String, MutableList<String>>()
    query.split('&').forEach { pair ->
      val parts = pair.split('=', limit = 2)
      val key = decodeQuery(parts[0]) ?: return null
      val value = decodeQuery(parts.getOrElse(1) { "" }) ?: return null
      values.getOrPut(key) { mutableListOf() } += value
    }
    return values
  }

  fun decodeQuery(value: String): String? =
    try {
      URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
      null
    }

  fun pageUrl(
    endpoint: String,
    query: Map<String, String>,
    page: Int,
  ): String =
    endpoint + "?" +
      (query + (ProductIssueHttpKeys.PAGE to page.toString())).entries.joinToString("&") {
        "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
      }

  fun validFullName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))

  fun validRepositoryApiUrl(
    url: String,
    repositoryName: String,
  ): Boolean =
    try {
      val uri = URI(url)
      if (!validAuthority(uri, API_HOST)) {
        false
      } else if (uri.query != null || uri.fragment != null) {
        false
      } else {
        uri.path.equals("/repos/$repositoryName", ignoreCase = true)
      }
    } catch (_: IllegalArgumentException) {
      false
    }

  fun validIssueUrl(
    url: String,
    number: Long,
    repositoryName: String?,
  ): Boolean =
    try {
      val uri = URI(url)
      if (!validAuthority(uri, WEB_HOST)) {
        false
      } else if (uri.query != null ||
        !uri.path.matches(Regex("/[^/]+/[^/]+/issues/$number"))
      ) {
        false
      } else {
        uri.fragment == null &&
          (repositoryName == null || uri.path.equals("/$repositoryName/issues/$number", ignoreCase = true))
      }
    } catch (_: IllegalArgumentException) {
      false
    }

  fun validCommentUrl(
    url: String,
    issueNumber: Long,
    commentId: Long,
    repositoryName: String,
  ): Boolean =
    try {
      val uri = URI(url)
      if (!validAuthority(uri, WEB_HOST)) {
        false
      } else if (uri.query != null ||
        !uri.path.matches(Regex("/[^/]+/[^/]+/issues/$issueNumber"))
      ) {
        false
      } else {
        uri.fragment == "issuecomment-$commentId" &&
          uri.path.equals("/$repositoryName/issues/$issueNumber", ignoreCase = true)
      }
    } catch (_: IllegalArgumentException) {
      false
    }

  fun positiveLong(value: Any?): Long? = (value as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 }

  fun nonNegativeLong(value: Any?): Long? = (value as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

  fun incidentMarker(body: String): String? = INCIDENT_MARKER.findAll(body).map { it.value }.toList().singleOrNull()

  fun issueApiUrl(number: Long): String = "$SKILL_BILL_PRODUCT_REPOSITORY_API/issues/$number"

  fun json(value: Map<String, Any?>): String = JsonCodec.mapToJsonString(value)

  fun failed(): ProductIssueResult.Failed = ProductIssueResult.Failed(ProductReportFailure.SUBMISSION_FAILED)

  sealed interface Lookup<out T>

  data class Value<T>(val value: T) : Lookup<T>

  data class LookupFailure(val result: ProductIssueResult) : Lookup<Nothing>

  sealed interface PageAdvance

  data object NoNextPage : PageAdvance

  data class NextPage(val page: Int) : PageAdvance

  data object InvalidPage : PageAdvance

  data class Repository(val fullName: String)

  data class Comment(val id: Long, val url: String, val body: String)

  data class Issue(
    val number: Long,
    val url: String,
    val body: String?,
    val pullRequest: Boolean = false,
    val originReportId: String? = null,
  )

  data class SearchPage(val issues: List<Issue>)

  val REPORT_ORIGIN = Regex("${Regex.escape(BlockIncidentReportKeys.REPORT_ORIGIN_MARKER)}(bir_[A-Za-z0-9_-]+) -->")
  const val API_HOST = "api.github.com"
  const val WEB_HOST = "github.com"
  const val GITHUB_API = "https://api.github.com"
  const val GITHUB_JSON_MEDIA_TYPE = "application/vnd.github+json"
  const val GITHUB_API_VERSION = "2022-11-28"
  const val GITHUB_API_VERSION_HEADER = "X-GitHub-Api-Version"
  const val ISSUES_URL = "$SKILL_BILL_PRODUCT_REPOSITORY_API/issues"
  const val PAGE_SIZE = 100
  const val SEARCH_PAGE_LIMIT = 10
  const val UNAUTHORIZED = 401
  const val FORBIDDEN = 403
  const val NOT_FOUND = 404
  const val TOO_MANY_REQUESTS = 429
  const val SERVER_ERROR_MIN = 500
  const val LINK_HEADER = "link"
  const val RATE_LIMIT_REMAINING = "x-ratelimit-remaining"
  const val RATE_LIMIT_RESET = "x-ratelimit-reset"
  const val RETRY_AFTER = "retry-after"
  const val REPORT_MARKER_PREFIX = "<!-- skill-bill-report-fingerprint:"
  const val REPORT_MARKER_SUFFIX = " -->"
  const val SUCCESS_MIN = 200
  const val SUCCESS_MAX = 299
  val SUCCESS_RANGE = SUCCESS_MIN..SUCCESS_MAX
  val INCIDENT_MARKER = Regex("<!-- skill-bill-incident:[^>]+ -->")
  val LINK_TARGET = Regex("^\\s*<([^>]+)>")
  val LINK_RELATION = Regex("\\brel\\s*=\\s*\"?([A-Za-z]+)\"?", RegexOption.IGNORE_CASE)

  data class CommentTarget(val issueNumber: Long, val body: String, val marker: String, val repositoryName: String)
}
