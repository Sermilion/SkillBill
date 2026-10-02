package skillbill.mcp.core

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.telemetryProxyRequestFailure
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.callToolError
import skillbill.mcp.shared.decodeJsonObject
import skillbill.mcp.shared.enabledTelemetryEnvironment
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.transport.RemoteTransportPort
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private enum class ProbeFailureCode : RuntimeFailureCode {
  PROBE,
}

class McpCaptureDiagnosticsTest {
  @Test
  fun `capture failure records one diagnostic naming the tool`() {
    var count = 0
    var diagnosticMessage = ""
    var captured: Throwable? = null
    val diagnostics =
      object : RuntimeDiagnostics {
        override fun warning(
          message: String,
          error: Throwable?,
        ) = Unit

        override fun error(
          message: String,
          error: Throwable?,
        ) {
          count += 1
          captured = error
          diagnosticMessage = message
        }
      }
    val failure = IllegalStateException("capture failed")

    recordCaptureFailure("quality_check_finished", { throw failure }, diagnostics)

    assertEquals(1, count)
    assertEquals("MCP telemetry capture failed for tool 'quality_check_finished'.", diagnosticMessage)
    assertSame(failure, captured)
  }

  @Test
  fun `dispatcher captures non-client tool failures and leaves client errors uncaptured`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-capture")
    val environment = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()

    val unsupported =
      McpRuntimeContext(
        requester = failingRequester(UnsupportedOperationException("transport unsupported")),
        environment = environment,
      ).callToolError(CAPTURED_TOOL)
    val clientError =
      McpRuntimeContext(
        requester = failingRequester(IllegalStateException("transport misconfigured")),
        environment = environment,
      ).callToolError(CAPTURED_TOOL)

    assertEquals(CAPTURED_TOOL, unsupported["tool"])
    assertEquals("transport unsupported", unsupported["error"])
    assertEquals(CAPTURED_TOOL, clientError["tool"])
    assertEquals("transport misconfigured", clientError["error"])
    assertEquals(listOf("UnsupportedOperationException"), capturedErrorTypes(dbPath))
  }

  @Test
  fun `dispatcher skips capture for shell-content codes and captures other coded failures by code label`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-capture-coded")
    val environment = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()

    val shellContent =
      McpRuntimeContext(
        requester =
          failingRequester(SkillBillRuntimeException(AgentAddonFailureCode.INVALID_SELECTION, "selection invalid")),
        environment = environment,
      ).callToolError(CAPTURED_TOOL)
    val probe =
      McpRuntimeContext(
        requester = failingRequester(SkillBillRuntimeException(ProbeFailureCode.PROBE, "probe failed")),
        environment = environment,
      ).callToolError(CAPTURED_TOOL)

    assertEquals(CAPTURED_TOOL, shellContent["tool"])
    assertEquals("selection invalid", shellContent["error"])
    assertEquals(CAPTURED_TOOL, probe["tool"])
    assertEquals("probe failed", probe["error"])
    assertEquals(listOf("ProbeFailureCode.PROBE"), capturedErrorTypes(dbPath))
  }

  @Test
  fun `dispatcher captures a proxy request failure and leaves an illegal argument uncaptured`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-capture-proxy")
    val environment = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()

    val proxyFailure =
      McpRuntimeContext(
        requester = failingRequester(telemetryProxyRequestFailure(500, "capabilities", "upstream")),
        environment = environment,
      ).callToolError(CAPTURED_TOOL)
    val illegalArgument =
      McpRuntimeContext(
        requester = failingRequester(IllegalArgumentException("bad argument")),
        environment = environment,
      ).callToolError(CAPTURED_TOOL)

    assertEquals("Telemetry proxy request failed at capabilities with HTTP 500: upstream", proxyFailure["error"])
    assertEquals("bad argument", illegalArgument["error"])
    assertEquals(listOf("TelemetryHttpFailureCode.PROXY_REQUEST_FAILED"), capturedErrorTypes(dbPath))
  }

  private fun failingRequester(failure: Exception): RemoteTransportPort =
    RemoteTransportPort { _, _, _, _ -> throw failure }

  private fun capturedErrorTypes(dbPath: Path): List<String> =
    ensureTestDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT payload_json FROM telemetry_outbox ORDER BY id").use { resultSet ->
          generateSequence { if (resultSet.next()) resultSet.getString("payload_json") else null }
            .map(::decodeJsonObject)
            .filter { payload -> payload["workflow_phase"] == CAPTURED_TOOL }
            .map { payload -> payload["error_type"].toString() }
            .toList()
        }
      }
    }

  private companion object {
    const val CAPTURED_TOOL = "telemetry_proxy_capabilities"
  }
}
