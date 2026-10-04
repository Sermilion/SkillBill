package skillbill.infrastructure.http
import skillbill.ports.process.model.ReleaseCatalogEntry
import skillbill.ports.process.model.ReleaseCatalogResult
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubReleaseCatalogAdapterTest {
  @Test
  fun `requests releases by repository id to avoid rename redirects and maps release fields`() {
    val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
    val body =
      """
      [
        {"tag_name":"v0.4.0","html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v0.4.0",
         "body":"notes","prerelease":false,"draft":false},
        {"tag_name":"v0.5.0-rc.1","html_url":"https://github.com/Sermilion/skill-bill/releases/tag/v0.5.0-rc.1",
         "prerelease":true,"draft":true},
        {"tag_name":"v0.6.0","prerelease":true}
      ]
      """.trimIndent()
    val adapter =
      GitHubReleaseCatalogAdapter { method, url, _, headers ->
        requests += Triple(method, url, headers)
        RemoteTransportResponse(statusCode = 200, body = body)
      }

    val result = adapter.listReleases()

    assertEquals(
      listOf(
        Triple(
          "GET",
          "https://api.github.com/repositories/1183084383/releases",
          mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "skill-bill-update-check"),
        ),
      ),
      requests,
    )
    assertEquals(
      ReleaseCatalogResult.Releases(
        listOf(
          ReleaseCatalogEntry.Release(
            tagName = "v0.4.0",
            url = "https://github.com/Sermilion/skill-bill/releases/tag/v0.4.0",
            notes = "notes",
            prerelease = false,
            draft = false,
          ),
          ReleaseCatalogEntry.Release(
            tagName = "v0.5.0-rc.1",
            url = "https://github.com/Sermilion/skill-bill/releases/tag/v0.5.0-rc.1",
            notes = null,
            prerelease = true,
            draft = true,
          ),
          ReleaseCatalogEntry.Malformed(prerelease = true, draft = false),
        ),
      ),
      result,
    )
  }

  @Test
  fun `maps rate limits and non-2xx statuses to failure reasons`() {
    assertEquals(
      ReleaseCatalogResult.Failure("GitHub API rate limit or access limit"),
      respondingWith(statusCode = 403, body = "").listReleases(),
    )
    assertEquals(
      ReleaseCatalogResult.Failure("GitHub API rate limit or access limit"),
      respondingWith(statusCode = 429, body = "").listReleases(),
    )
    assertEquals(
      ReleaseCatalogResult.Failure("GitHub Releases request failed with HTTP 503"),
      respondingWith(statusCode = 503, body = "").listReleases(),
    )
  }

  @Test
  fun `maps unparseable and non-object payloads to the malformed payload reason`() {
    val malformed = ReleaseCatalogResult.Failure("malformed GitHub Releases payload")
    assertEquals(malformed, respondingWith(statusCode = 200, body = "not-json").listReleases())
    assertEquals(malformed, respondingWith(statusCode = 200, body = "{}").listReleases())
    assertEquals(malformed, respondingWith(statusCode = 200, body = "[\"v0.4.0\"]").listReleases())
  }

  @Test
  fun `whitespace formatted empty release arrays are not treated as malformed`() {
    assertEquals(
      ReleaseCatalogResult.Releases(emptyList()),
      respondingWith(statusCode = 200, body = "[ ]").listReleases(),
    )
  }

  @Test
  fun `maps transport failures to the network failure reason`() {
    assertEquals(
      ReleaseCatalogResult.Failure("network failure: connection reset"),
      GitHubReleaseCatalogAdapter { _, _, _, _ -> throw IOException("connection reset") }.listReleases(),
    )
    assertEquals(
      ReleaseCatalogResult.Failure("network failure: IllegalArgumentException"),
      GitHubReleaseCatalogAdapter { _, _, _, _ -> throw IllegalArgumentException() }.listReleases(),
    )
  }

  @Test
  fun `interruption is rethrown`() {
    assertFailsWith<InterruptedException> {
      GitHubReleaseCatalogAdapter { _, _, _, _ -> throw InterruptedException("cancelled") }.listReleases()
    }
  }
}

private fun respondingWith(
  statusCode: Int,
  body: String,
): GitHubReleaseCatalogAdapter =
  GitHubReleaseCatalogAdapter(RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(statusCode, body) })
