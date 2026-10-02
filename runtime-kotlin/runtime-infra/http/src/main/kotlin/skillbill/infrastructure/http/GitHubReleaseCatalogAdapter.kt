package skillbill.infrastructure.http
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.ports.process.ReleaseCatalogPort
import skillbill.ports.process.model.ReleaseCatalogEntry
import skillbill.ports.process.model.ReleaseCatalogResult
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import java.io.IOException

@Inject
class GitHubReleaseCatalogAdapter(
  private val requester: RemoteTransportPort,
) : ReleaseCatalogPort {
  override fun listReleases(): ReleaseCatalogResult {
    val response =
      try {
        requester.execute(
          method = "GET",
          url = RELEASES_URL,
          bodyJson = null,
          headers =
            mapOf(
              HttpHeaders.ACCEPT to GITHUB_JSON_MEDIA_TYPE,
              HttpHeaders.USER_AGENT to UPDATE_CHECK_USER_AGENT,
            ),
        )
      } catch (error: IOException) {
        return networkFailure(error)
      } catch (error: InterruptedException) {
        throw error
      } catch (error: IllegalArgumentException) {
        return networkFailure(error)
      }
    return parseResponse(response)
  }

  private fun parseResponse(response: RemoteTransportResponse): ReleaseCatalogResult {
    val statusFailure =
      when {
        response.statusCode == HTTP_FORBIDDEN || response.statusCode == HTTP_TOO_MANY_REQUESTS ->
          "GitHub API rate limit or access limit"
        response.statusCode !in HTTP_SUCCESS_RANGE ->
          "GitHub Releases request failed with HTTP ${response.statusCode}"
        else -> null
      }
    if (statusFailure != null) {
      return ReleaseCatalogResult.Failure(statusFailure)
    }
    val parsed =
      try {
        JsonCodec.parseJsonArrayStrict(response.body)
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        return malformedPayload()
      }
    return releasesFrom(parsed)
  }

  private fun releasesFrom(parsed: List<Any?>): ReleaseCatalogResult {
    val entries = parsed.map(JsonCodec::anyToStringAnyMap)
    if (entries.any { entry -> entry == null }) {
      return malformedPayload()
    }
    return ReleaseCatalogResult.Releases(entries.filterNotNull().map(::releaseEntry))
  }

  private fun releaseEntry(entry: Map<String, Any?>): ReleaseCatalogEntry {
    val prerelease = entry[PRERELEASE_KEY] as? Boolean ?: false
    val draft = entry[DRAFT_KEY] as? Boolean ?: false
    val tagName = entry[TAG_NAME_KEY] as? String
    val url = entry[HTML_URL_KEY] as? String
    if (tagName == null || url == null) {
      return ReleaseCatalogEntry.Malformed(prerelease = prerelease, draft = draft)
    }
    return ReleaseCatalogEntry.Release(
      tagName = tagName,
      url = url,
      notes = entry[BODY_KEY] as? String,
      prerelease = prerelease,
      draft = draft,
    )
  }

  private fun malformedPayload(): ReleaseCatalogResult =
    ReleaseCatalogResult.Failure("malformed GitHub Releases payload")

  private fun networkFailure(error: Exception): ReleaseCatalogResult =
    ReleaseCatalogResult.Failure("network failure: ${errorMessage(error)}")

  private fun errorMessage(error: Exception): String =
    error.message.orEmpty().ifBlank { error.failureCodeLabel() ?: error::class.simpleName.orEmpty() }

  private companion object {
    const val RELEASES_URL: String = "https://api.github.com/repos/Sermilion/skill-bill/releases"
    const val GITHUB_JSON_MEDIA_TYPE: String = "application/vnd.github+json"
    const val UPDATE_CHECK_USER_AGENT: String = "skill-bill-update-check"
    const val HTTP_FORBIDDEN: Int = 403
    const val HTTP_TOO_MANY_REQUESTS: Int = 429
    const val TAG_NAME_KEY: String = "tag_name"
    const val HTML_URL_KEY: String = "html_url"
    const val BODY_KEY: String = "body"
    const val PRERELEASE_KEY: String = "prerelease"
    const val DRAFT_KEY: String = "draft"
  }
}
