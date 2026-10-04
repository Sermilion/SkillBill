package skillbill.mcp.core

import skillbill.contracts.learning.LearningPayloadKeys
import skillbill.error.core.ReviewAttributionFailureCode
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
    val invalidArgument =
      McpRuntimeContext(environment = environment)
        .callToolError("review_stats", mapOf("review_run_id" to 7))

    assertEquals(CAPTURED_TOOL, unsupported["tool"])
    assertEquals("transport unsupported", unsupported["error"])
    assertEquals(CAPTURED_TOOL, clientError["tool"])
    assertEquals("transport misconfigured", clientError["error"])
    assertEquals("review_stats", invalidArgument["tool"])
    assertEquals(
      "MCP tool 'review_stats' argument 'review_run_id': must be a string",
      invalidArgument["error"],
    )
    assertEquals(listOf("UnsupportedOperationException"), capturedErrorTypes(dbPath))
    assertEquals(emptyList(), capturedErrorTypes(dbPath, "review_stats"))
  }

  @Test
  fun `dispatcher returns coded malformed attribution errors without capturing telemetry`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-capture-attribution")
    val environment = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()
    val message =
      "Review attribution vocabulary 'pack_skill_names' contains the malformed entry 'Bad Name' " +
        "while resolving 'bill-kmp-code-review'."

    val error =
      McpRuntimeContext(
        requester =
          failingRequester(
            SkillBillRuntimeException(ReviewAttributionFailureCode.MALFORMED_VOCABULARY, message),
          ),
        environment = environment,
      ).callToolError(CAPTURED_TOOL)

    assertEquals(CAPTURED_TOOL, error["tool"])
    assertEquals(message, error["error"])
    assertEquals(emptyList(), capturedErrorTypes(dbPath))
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
  fun `dispatcher returns learning source errors without capturing telemetry`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-capture-learning-source")
    val environment = enabledTelemetryEnvironment(tempDir)
    val dbPath = tempDir.resolve("metrics.db")
    ensureTestDatabase(dbPath).close()

    val error =
      McpRuntimeContext(
        requester = failingRequester(UnsupportedOperationException("transport unsupported")),
        environment = environment,
      ).callToolError(
        "add_learning",
        mapOf(
          LearningPayloadKeys.SCOPE to "global",
          LearningPayloadKeys.TITLE to "A learning",
          LearningPayloadKeys.RULE_TEXT to "Use explicit wording.",
          LearningPayloadKeys.SOURCE_REVIEW_RUN_ID to "rvw-missing",
          LearningPayloadKeys.SOURCE_FINDING_ID to "F-missing",
        ),
      )

    assertEquals("add_learning", error["tool"])
    assertEquals(
      "Unknown learning source 'rvw-missing:F-missing'. Import the review and finding first.",
      error["error"],
    )
    assertEquals(emptyList(), capturedErrorTypes(dbPath, "add_learning"))
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

  private fun capturedErrorTypes(
    dbPath: Path,
    toolName: String = CAPTURED_TOOL,
  ): List<String> =
    ensureTestDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT payload_json FROM telemetry_outbox ORDER BY id").use { resultSet ->
          generateSequence { if (resultSet.next()) resultSet.getString("payload_json") else null }
            .map(::decodeJsonObject)
            .filter { payload -> payload["workflow_phase"] == toolName }
            .map { payload -> payload["error_type"].toString() }
            .toList()
        }
      }
    }

  private companion object {
    const val CAPTURED_TOOL = "telemetry_proxy_capabilities"
  }
}
