package skillbill.infrastructure.http
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.TelemetryHttpFailureCode
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpTelemetryTypedErrorsTest {
  @Test
  fun `capabilities non-2xx outside 404 and 405 raises typed request failure`() {
    val requester = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(statusCode = 500, body = "upstream") }
    val settings = settingsWithProxy("https://telemetry.example.dev/ingest")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        client(requester).fetchProxyCapabilities(settings)
      }

    assertEquals(TelemetryHttpFailureCode.PROXY_REQUEST_FAILED, error.code)
    assertTrue(error.message.orEmpty().contains("with HTTP 500: "), error.message.orEmpty())
  }

  @Test
  fun `capabilities invalid JSON raises typed invalid response`() {
    val requester = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(statusCode = 200, body = "not-json") }
    val settings = settingsWithProxy("https://telemetry.example.dev/ingest")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        client(requester).fetchProxyCapabilities(settings)
      }

    assertEquals(TelemetryHttpFailureCode.PROXY_INVALID_RESPONSE, error.code)
    assertTrue(error.message.orEmpty().contains("invalid JSON"))
  }

  @Test
  fun `capabilities non-object JSON raises typed invalid response`() {
    val requester = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(statusCode = 200, body = "[]") }
    val settings = settingsWithProxy("https://telemetry.example.dev/ingest")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        client(requester).fetchProxyCapabilities(settings)
      }

    assertEquals(TelemetryHttpFailureCode.PROXY_INVALID_RESPONSE, error.code)
    assertTrue(error.message.orEmpty().contains("non-object"))
  }

  @Test
  fun `capabilities blank 2xx body raises typed invalid response`() {
    val requester = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(statusCode = 200, body = "") }
    val settings = settingsWithProxy("https://telemetry.example.dev/ingest")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        client(requester).fetchProxyCapabilities(settings)
      }

    assertEquals(TelemetryHttpFailureCode.PROXY_INVALID_RESPONSE, error.code)
    assertTrue(error.message.orEmpty().endsWith(": empty response body"), error.message.orEmpty())
  }

  @Test
  fun `blank relay URL raises typed unconfigured error`() {
    val settings = settingsWithProxy("")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        client(RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(200, "{}") })
          .fetchProxyCapabilities(settings)
      }

    assertEquals(TelemetryHttpFailureCode.RELAY_URL_UNCONFIGURED, error.code)
  }

  @Test
  fun `remote stats non-2xx raises typed request failure`() {
    val requester =
      RemoteTransportPort { _, url, _, _ ->
        if (url.endsWith("/capabilities")) {
          RemoteTransportResponse(statusCode = 200, body = capabilitiesBody())
        } else {
          RemoteTransportResponse(statusCode = 503, body = "unavailable")
        }
      }
    val settings = settingsWithProxy("https://telemetry.example.dev/ingest")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        client(requester).fetchRemoteStats(
          settings = settings,
          request =
            RemoteStatsRequest(
              workflow = "bill-feature-verify",
              dateFrom = "2026-04-01",
              dateTo = "2026-04-22",
            ),
        )
      }

    assertEquals(TelemetryHttpFailureCode.PROXY_REQUEST_FAILED, error.code)
    assertTrue(error.message.orEmpty().contains("with HTTP 503: "), error.message.orEmpty())
  }

  @Test
  fun `sendBatch with out-of-range status raises the invalid transport outcome failure`() {
    val requester = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(statusCode = 99, body = "") }
    val settings = settingsWithProxy("https://telemetry.example.dev/ingest")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        client(requester).sendBatch(settings, emptyList())
      }

    assertEquals(TelemetryHttpFailureCode.INVALID_TRANSPORT_OUTCOME, error.code)
    assertEquals("Telemetry transport returned 99, which is not a valid HTTP status code.", error.message)
  }
}

private fun settingsWithProxy(proxyUrl: String): TelemetrySettings =
  TelemetrySettings(
    configPath = Files.createTempFile("telemetry-typed-errors", ".json").toFileLocation(),
    level = "anonymous",
    enabled = true,
    installId = "test-install-id",
    proxyUrl = proxyUrl,
    customProxyUrl = proxyUrl.ifBlank { null },
    batchSize = 50,
  )

private fun capabilitiesBody(): String =
  """
  {
    "contract_version": "2",
    "supports_ingest": true,
    "supports_stats": true,
    "supports_event_deduplication": true
  }
  """.trimIndent()

private fun client(requester: RemoteTransportPort): HttpTelemetryClient =
  HttpTelemetryClient(
    requester = requester,
    environmentContext = EnvironmentContext(environment = emptyMap()),
    clock = Clock.systemUTC(),
    diagnostics = SilentTypedErrorsDiagnostics,
  )

private object SilentTypedErrorsDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
