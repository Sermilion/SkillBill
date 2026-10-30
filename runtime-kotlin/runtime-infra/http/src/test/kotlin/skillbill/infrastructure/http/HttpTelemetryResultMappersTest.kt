package skillbill.infrastructure.http

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.TelemetryProxyPayloadKeys
import skillbill.ports.diagnostics.RuntimeDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpTelemetryResultMappersTest {
  @Test
  fun `stats_auth_required from proxy stays in additionalFields`() {
    val capabilities =
      mapOf(
        SharedPayloadKeys.CONTRACT_VERSION to "1",
        TelemetryProxyPayloadKeys.SOURCE to TelemetryProxyPayloadKeys.REMOTE_PROXY,
        TelemetryProxyPayloadKeys.STATS_AUTH_REQUIRED to true,
      ).toTelemetryProxyCapabilities(
        proxyUrl = "https://telemetry.example.dev/ingest",
        capabilitiesUrl = "https://telemetry.example.dev/ingest/capabilities",
        diagnostics = SilentDiagnostics,
      )

    assertEquals(true, capabilities.additionalFields[TelemetryProxyPayloadKeys.STATS_AUTH_REQUIRED])
  }
}

private object SilentDiagnostics : RuntimeDiagnostics {
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
