package skillbill.mcp.core

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.assertStrictSchemaCoveragePublished
import skillbill.mcp.shared.callTool
import skillbill.mcp.shared.decodeResponse
import skillbill.mcp.shared.disabledTelemetryEnvironment
import skillbill.mcp.shared.expectedToolInventory
import skillbill.mcp.shared.fieldMap
import skillbill.mcp.shared.priorityStrictToolNames
import skillbill.mcp.shared.properties
import skillbill.mcp.shared.schemaFor
import skillbill.mcp.shared.toolPayload
import skillbill.mcp.shared.toolsList
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpStdioServerTest {
  private val context: McpRuntimeContext by lazy {
    val tempDir = Files.createTempDirectory("skillbill-mcp-stdio")
    McpRuntimeContext(environment = disabledTelemetryEnvironment(tempDir), userHome = tempDir)
  }

  @Test
  fun `initialize returns MCP server capabilities`() {
    val rawResponse =
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
        context.mcpComponent(),
      )
    val response = decodeResponse(rawResponse)
    val result = response.fieldMap("result")

    assertTrue(requireNotNull(rawResponse).contains(""""jsonrpc":"2.0""""))
    assertEquals(1, response["id"])
    assertEquals("2025-11-25", result["protocolVersion"])
    assertEquals("skill-bill", result.fieldMap("serverInfo")["name"])
    assertTrue(result.fieldMap("capabilities").containsKey("tools"))
  }

  @Test
  fun `tools list matches golden mcp-tools-list fixture`() {
    val actualTools = toolsList()
    val goldenText = Files.readString(Path.of("src/test/resources/golden/mcp-tools-list.json"))
    val goldenObject = requireNotNull(JsonCodec.parseObjectOrNull(goldenText))
    val goldenTools = JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(goldenObject))?.get("tools")
    assertEquals(goldenTools, actualTools)
  }

  @Test
  fun `tools list exposes the expected inventory`() {
    val names = toolsList().map { tool -> requireNotNull(JsonCodec.anyToStringAnyMap(tool))["name"] }

    assertEquals(expectedToolInventory, names)
  }

  @Test
  fun `priority validating persisting and telemetry tools expose strict input schemas`() {
    val tools = toolsList()

    priorityStrictToolNames.forEach { toolName ->
      val schema = tools.schemaFor(toolName)

      assertEquals("object", schema["type"], toolName)
      assertEquals(false, schema["additionalProperties"], toolName)
    }
  }

  @Test
  fun `strict schema coverage publishes required arguments and enums`() {
    assertStrictSchemaCoveragePublished(toolsList())
  }

  @Test
  fun `zero argument workflow tools expose strict empty objects`() {
    val tools = toolsList()

    listOf(
      "feature_verify_workflow_latest",
    ).forEach { toolName ->
      val schema = tools.schemaFor(toolName)

      assertEquals(false, schema["additionalProperties"], toolName)
      assertEquals(emptyMap<String, Any?>(), schema.properties(), toolName)
      assertEquals(emptyList<String>(), schema["required"], toolName)
    }
  }

  @Test
  fun `audit repair can complete and block through the advertised settlement tools`() {
    val phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
    val identity =
      mapOf(
        SharedPayloadKeys.WORKFLOW_ID to "wftr-audit-repair",
        SharedPayloadKeys.PHASE_ID to phaseId,
        SharedPayloadKeys.ATTEMPT to 2,
      )
    val completed =
      toolPayload(
        context.callTool(
          "feature_task_phase_complete",
          identity + (SharedPayloadKeys.VALUE to "AC-008: repaired admission at both transactions."),
        ),
      )
    assertEquals("ok", completed[SharedPayloadKeys.STATUS], completed.toString())
    assertEquals(phaseId, completed[SharedPayloadKeys.PHASE_ID])
    assertEquals(2, completed[SharedPayloadKeys.ATTEMPT])
    val blocked =
      toolPayload(
        context.callTool(
          "feature_task_phase_block",
          identity +
            mapOf(
              SharedPayloadKeys.ATTEMPT to 3,
              UpdateCheckPayloadKeys.REASON to "Required dependency is unavailable.",
            ),
        ),
      )
    assertEquals("ok", blocked[SharedPayloadKeys.STATUS], blocked.toString())
    assertEquals(phaseId, blocked[SharedPayloadKeys.PHASE_ID])
    assertEquals(3, blocked[SharedPayloadKeys.ATTEMPT])
    val settlements = context.mcpComponent().featureTaskPhaseSettlementService
    val completedEnvelope = assertNotNull(settlements.findEnvelope("wftr-audit-repair", phaseId, 2)).envelope
    val blockedEnvelope = assertNotNull(settlements.findEnvelope("wftr-audit-repair", phaseId, 3)).envelope
    assertEquals("completed", completedEnvelope[SharedPayloadKeys.STATUS])
    assertEquals("blocked", blockedEnvelope[SharedPayloadKeys.STATUS])
    assertEquals(
      "AC-008: repaired admission at both transactions.",
      completedEnvelope.fieldMap(SharedPayloadKeys.PRODUCED_OUTPUTS)[SharedPayloadKeys.VALUE],
    )
    assertEquals(
      "Required dependency is unavailable.",
      blockedEnvelope.fieldMap(SharedPayloadKeys.PRODUCED_OUTPUTS)[SharedPayloadKeys.VALUE],
    )
  }

  @Test
  fun `strict tools reject unknown arguments at the stdio boundary`() {
    val result =
      context.callTool(
        "resolve_learnings",
        mapOf("repo" to "skill-bill", "unexpected" to true),
      )
    val errorPayload = toolPayload(result)

    assertEquals(true, result["isError"])
    assertEquals("resolve_learnings", errorPayload["tool"])
    assertContains(errorPayload["error"].toString(), "unexpected")
  }

  @Test
  fun `strict tools reject unknown nested arguments at the stdio boundary`() {
    val result =
      context.callTool(
        "feature_verify_workflow_update",
        mapOf(
          "workflow_id" to "wfl-test",
          "workflow_status" to "running",
          "current_step_id" to "code_review",
          "step_updates" to
            listOf(
              mapOf(
                "step_id" to "code_review",
                "status" to "running",
                "attempt_count" to 1,
                "unexpected" to true,
              ),
            ),
        ),
      )
    val errorPayload = toolPayload(result)

    assertEquals(true, result["isError"])
    assertContains(errorPayload["error"].toString(), "unexpected")
  }

  @Test
  fun `mistyped open object arguments name the tool that rejected them`() {
    val result = context.callTool("review_stats", mapOf("review_run_id" to 7))
    val errorPayload = toolPayload(result)

    assertEquals(true, result["isError"])
    assertEquals("review_stats", errorPayload["tool"])
    assertEquals(
      "MCP tool 'review_stats' argument 'review_run_id': must be a string",
      errorPayload["error"],
    )
  }
}
