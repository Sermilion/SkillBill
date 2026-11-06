package skillbill.mcp.core

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.persistence.RuntimeMcpEnvironmentKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.mcp.featuretask.ValidationCheckPayloadKeys
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.callToolError
import skillbill.mcp.shared.decodeResponse
import skillbill.mcp.shared.disabledTelemetryEnvironment
import skillbill.mcp.shared.fieldMap
import skillbill.mcp.shared.properties
import skillbill.mcp.shared.schemaFor
import skillbill.mcp.shared.toolNamedOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpValidationCheckToolTest {
  @Test
  fun `a server without a validation run hides the tool and rejects a call`() {
    val context = context(emptyMap())

    assertNull(toolsList(context).toolNamedOrNull(TOOL))
    val payload =
      context.callToolError(
        TOOL,
        mapOf(
          ValidationCheckPayloadKeys.EVENT to "planned",
          ValidationCheckPayloadKeys.CHECK_ID to "unit-tests",
          ValidationCheckPayloadKeys.NAME to "Unit tests",
          ValidationCheckPayloadKeys.COMMAND to "./gradlew test",
          ValidationCheckPayloadKeys.WORKING_DIR to ".",
        ),
      )
    assertEquals("rejected", payload[SharedPayloadKeys.STATUS])
    assertEquals("no_run_identity", payload[UpdateCheckPayloadKeys.REASON])
  }

  @Test
  fun `a server launched for a validation run lists the tool with its schema`() {
    val context = context(mapOf(RuntimeMcpEnvironmentKeys.VALIDATION_INVOCATION_ID to "validation-run"))

    val schema = toolsList(context).schemaFor(TOOL)
    assertEquals(false, schema["additionalProperties"])
    assertEquals(
      setOf("event", "check_id", "name", "command", "working_dir"),
      (schema["required"] as List<*>).toSet(),
    )
    assertEquals(
      "^[a-z0-9][a-z0-9._-]{0,63}$",
      schema.properties().fieldMap(ValidationCheckPayloadKeys.CHECK_ID)["pattern"],
    )
  }

  private fun context(extra: Map<String, String>): McpRuntimeContext {
    val tempDir = Files.createTempDirectory("skillbill-mcp-validation-check")
    return McpRuntimeContext(environment = disabledTelemetryEnvironment(tempDir) + extra, userHome = tempDir)
  }

  private fun toolsList(context: McpRuntimeContext): List<*> =
    decodeResponse(
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        context.mcpComponent(),
      ),
    ).fieldMap("result")["tools"] as List<*>

  private companion object {
    const val TOOL = "validation_check"
  }
}
