package skillbill.infrastructure.http
import skillbill.contracts.time.JvmSystemClock
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.toFileLocation
import skillbill.ports.telemetry.model.RemoteTransportResponse
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.transport.RemoteTransportPort
import skillbill.telemetry.model.RemoteStatsRequest
import skillbill.telemetry.model.TelemetryDeliveryOutcome
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTelemetryClientTest {
  @Test
  fun `fetchRemoteStats posts proxy contract payload`() {
    val requests = mutableListOf<Triple<String, String, String?>>()
    val requester = remoteStatsRequester(requests)
    val settings = telemetrySettings(Files.createTempFile("telemetry", ".json"))

    val payload =
      client(
        requester,
        environment = mapOf("SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN" to "stats-token-123"),
      ).fetchRemoteStats(
        settings = settings,
        request =
          RemoteStatsRequest(
            workflow = "bill-feature-verify",
            dateFrom = "2026-04-01",
            dateTo = "2026-04-22",
          ),
      )

    assertEquals("bill-feature-verify", payload.workflow)
    assertEquals(14, payload.metrics["started_runs"])
    assertEquals(2, requests.size)
    assertEquals("GET", requests[0].first)
    assertEquals("POST", requests[1].first)
    assertNotNull(payload.capabilities)
  }

  @Test
  fun `sendBatch preserves proxy request body bytes`() {
    var capturedBody: String? = null
    val requester =
      RemoteTransportPort { _, _, bodyJson, _ ->
        capturedBody = bodyJson
        RemoteTransportResponse(statusCode = 200, body = "")
      }

    client(requester).sendBatch(
      settings = telemetrySettings(Files.createTempFile("telemetry-batch-body", ".json")),
      rows =
        listOf(
          TelemetryOutboxRecord(
            id = 1,
            eventName = "skillbill_goal_finished",
            payloadJson = """{"name":"ok"}""",
            createdAt = "2026-04-23 00:00:00",
            syncedAt = null,
            lastError = "",
            skillBillVersion = null,
            eventUuid = "",
          ),
        ),
    )

    val expectedBody =
      "{\"batch\":[{\"event\":\"skillbill_goal_finished\",\"distinct_id\":\"test-install-id\"," +
        "\"properties\":{\"name\":\"ok\",\"install_id\":\"test-install-id\"," +
        "\"${'$'}process_person_profile\":false},\"timestamp\":\"2026-04-23 00:00:00\"}]}"
    assertEquals(expectedBody, capturedBody)
  }

  @Test
  fun `fetchProxyCapabilities falls back to default contract on 404 and records substitution`() {
    val diagnostics = TelemetryRecordingDiagnostics()
    val requester = RemoteTransportPort { _, _, _, _ -> RemoteTransportResponse(statusCode = 404, body = "") }
    val settings = telemetrySettings(Files.createTempFile("telemetry-capabilities", ".json"), customProxyUrl = null)

    val payload = client(requester, diagnostics = diagnostics).fetchProxyCapabilities(settings)

    assertEquals("0", payload.contractVersion)
    assertEquals(false, payload.supportsStats)
    assertEquals(1, diagnostics.warnings.size)
    assertTrue(diagnostics.warnings.single().contains("seam=telemetry.capabilities.fallback"))
    assertTrue(diagnostics.warnings.single().contains("expected=capabilities response"))
    assertTrue(diagnostics.warnings.single().contains("used=typed default"))
  }

  @Test
  fun `a relay that cannot carry the deduplication property fails the ingest handshake loudly`() {
    val settings = telemetrySettings(Files.createTempFile("telemetry-dedup-capability", ".json"))
    val refusing = client(ingestCapabilitiesRequester(deduplicationSupported = false))
    val silentOlderRelay = client(ingestCapabilitiesRequester(deduplicationSupported = null))

    val error = assertFailsWith<IllegalArgumentException> { refusing.fetchProxyCapabilities(settings) }

    assertTrue(
      error.message.orEmpty().contains("event deduplication"),
      "The refusal must name the missing property, not fail as a generic transport error.",
    )
    assertEquals(
      true,
      silentOlderRelay.fetchProxyCapabilities(settings).supportsEventDeduplication,
      "A relay that omits the field forwards properties verbatim and must keep working.",
    )
  }

  @Test
  fun `a rejected batch carries the relay status and reason`() {
    val requester =
      RemoteTransportPort { _, _, _, _ ->
        RemoteTransportResponse(statusCode = 422, body = """{"error":"unknown event property"}""")
      }
    val settings = telemetrySettings(Files.createTempFile("telemetry-rejection", ".json"))

    val report = client(requester).sendBatch(settings, emptyList())

    assertEquals(TelemetryDeliveryOutcome.REJECTED, report.outcome)
    assertTrue(report.detail.contains("422"), "The recorded refusal must name the relay status code.")
    assertTrue(report.detail.contains("unknown event property"), "The relay's reason must survive into the record.")
  }

  @Test
  fun `fetchRemoteStats preserves explicit null stats capabilities`() {
    val requester =
      RemoteTransportPort { _, url, _, _ ->
        if (url.endsWith("/capabilities")) {
          capabilitiesResponse()
        } else {
          remoteStatsResponseWithNullCapabilities()
        }
      }
    val settings = telemetrySettings(Files.createTempFile("telemetry-null-capabilities", ".json"))

    val payload =
      client(requester).fetchRemoteStats(
        settings = settings,
        request =
          RemoteStatsRequest(
            workflow = "bill-feature-verify",
            dateFrom = "2026-04-01",
            dateTo = "2026-04-22",
          ),
      )

    assertEquals(true, payload.metrics.containsKey("capabilities"))
    assertNull(payload.metrics["capabilities"])
  }
}

private fun client(
  requester: RemoteTransportPort,
  environment: Map<String, String> = emptyMap(),
  diagnostics: RuntimeDiagnostics = SilentTelemetryDiagnostics,
): HttpTelemetryClient =
  HttpTelemetryClient(
    requester = requester,
    environmentContext = EnvironmentContext(environment = environment),
    clock = JvmSystemClock,
    diagnostics = diagnostics,
  )

private class TelemetryRecordingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private object SilentTelemetryDiagnostics : RuntimeDiagnostics {
  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}

private fun remoteStatsRequester(requests: MutableList<Triple<String, String, String?>>): RemoteTransportPort =
  RemoteTransportPort { method, url, bodyJson, _ ->
    requests += Triple(method, url, bodyJson)
    if (url.endsWith("/capabilities")) capabilitiesResponse() else remoteStatsResponse()
  }

private fun ingestCapabilitiesRequester(deduplicationSupported: Boolean?): RemoteTransportPort {
  val deduplicationField =
    deduplicationSupported?.let { ""","supports_event_deduplication":$it""" }.orEmpty()
  return RemoteTransportPort { _, _, _, _ ->
    RemoteTransportResponse(
      statusCode = 200,
      body = """{"contract_version":"2","supports_ingest":true$deduplicationField}""",
    )
  }
}

private fun capabilitiesResponse(): RemoteTransportResponse =
  RemoteTransportResponse(
    statusCode = 200,
    body =
      """
      {
        "contract_version": "1",
        "supports_ingest": true,
        "supports_stats": true,
        "supported_workflows": ["bill-feature-verify", "feature-task-runtime"]
      }
      """.trimIndent(),
  )

private fun remoteStatsResponse(): RemoteTransportResponse =
  RemoteTransportResponse(
    statusCode = 200,
    body =
      """
      {
        "status": "ok",
        "workflow": "bill-feature-verify",
        "source": "remote_proxy",
        "started_runs": 14,
        "finished_runs": 12,
        "in_progress_runs": 2
      }
      """.trimIndent(),
  )

private fun remoteStatsResponseWithNullCapabilities(): RemoteTransportResponse =
  RemoteTransportResponse(
    statusCode = 200,
    body =
      """
      {
        "status": "ok",
        "workflow": "bill-feature-verify",
        "source": "remote_proxy",
        "started_runs": 14,
        "capabilities": null
      }
      """.trimIndent(),
  )

private fun telemetrySettings(
  configPath: Path,
  proxyUrl: String = "https://telemetry.example.dev/ingest",
  customProxyUrl: String? = proxyUrl,
): TelemetrySettings =
  TelemetrySettings(
    configPath = configPath.toFileLocation(),
    level = "anonymous",
    enabled = true,
    installId = "test-install-id",
    proxyUrl = proxyUrl,
    customProxyUrl = customProxyUrl,
    batchSize = 50,
  )
