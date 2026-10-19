package skillbill.mcp.shared

import skillbill.contracts.JsonCodec
import skillbill.mcp.core.McpStdioServer
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal fun assertStrictSchemaCoveragePublished(tools: List<*>) {
  assertQualityCheckSchemaCoverage(tools)
  assertFeatureVerifySchemaCoverage(tools)
  assertTelemetryRemoteStatsSchemaCoverage(tools)
  assertGoalStatsSchemaCoverage(tools)
  assertMiscToolSchemaCoverage(tools)
}

private fun assertQualityCheckSchemaCoverage(tools: List<*>) {
  tools.schemaFor("quality_check_finished").assertRequired(
    "session_id",
    "result",
    "routed_skill",
    "detected_stack",
    "fallback",
    "scope_type",
  )
  assertEquals(
    listOf("pass", "fail", "skipped", "unsupported_stack"),
    tools.schemaFor("quality_check_finished").properties().enumFor("result"),
  )
  assertEquals(
    listOf("files", "working_tree", "branch_diff", "repo"),
    tools.schemaFor("quality_check_started").properties().enumFor("scope_type"),
  )
  tools.schemaFor("quality_check_started").assertRequired("routed_skill", "detected_stack", "fallback")
}

private fun assertFeatureVerifySchemaCoverage(tools: List<*>) {
  assertEquals(
    listOf("completed", "abandoned_at_review", "abandoned_at_audit", "error"),
    tools.schemaFor("feature_verify_finished").properties().enumFor("completion_status"),
  )
  assertEquals(
    listOf("all_pass", "had_gaps", "skipped"),
    tools.schemaFor("feature_verify_finished").properties().enumFor("audit_result"),
  )
  assertEquals(
    listOf(
      "collect_inputs",
      "extract_criteria",
      "gather_diff",
      "feature_flag_audit",
      "code_review",
      "unit_test_value_check",
      "completeness_audit",
      "qa",
      "verdict",
      "finish",
    ),
    tools.schemaFor("feature_verify_workflow_update").properties().enumFor("current_step_id"),
  )
}

private fun assertTelemetryRemoteStatsSchemaCoverage(tools: List<*>) {
  tools.schemaFor("telemetry_remote_stats").assertRequired("workflow")
  assertEquals(
    listOf(
      "verify",
      "bill-feature-verify",
      "feature-task-runtime",
    ),
    tools.schemaFor("telemetry_remote_stats").properties().enumFor("workflow"),
  )
  assertEquals(
    listOf("", "day", "week"),
    tools.schemaFor("telemetry_remote_stats").properties().enumFor("group_by"),
  )
}

private fun assertGoalStatsSchemaCoverage(tools: List<*>) {
  assertEquals(false, tools.schemaFor("goal_stats")["additionalProperties"])
  assertEquals(emptyList<String>(), tools.schemaFor("goal_stats")["required"])
  assertEquals(
    setOf("since", "date_from", "date_to", "group_by"),
    tools.schemaFor("goal_stats").properties().keys,
  )
  assertEquals(
    listOf("", "day", "week"),
    tools.schemaFor("goal_stats").properties().enumFor("group_by"),
  )
}

private fun assertMiscToolSchemaCoverage(tools: List<*>) {
  tools.schemaFor("new_skill_scaffold").assertRequired("payload")
  tools.schemaFor("import_review").assertRequired("review_text")
  tools.schemaFor("triage_findings").assertRequired("review_run_id", "decisions")
}

internal val expectedToolInventory =
  listOf(
    "add_learning",
    "doctor",
    "feature_task_phase_block",
    "feature_task_phase_complete",
    "feature_verify_finished",
    "feature_verify_stats",
    "feature_verify_started",
    "feature_verify_workflow_get",
    "feature_verify_workflow_latest",
    "feature_verify_workflow_list",
    "feature_verify_workflow_continue",
    "feature_verify_workflow_open",
    "feature_verify_workflow_resume",
    "feature_verify_workflow_update",
    "goal_stats",
    "import_review",
    "new_skill_scaffold",
    "pr_description_generated",
    "quality_check_finished",
    "quality_check_started",
    "resolve_learnings",
    "review_stats",
    "telemetry_proxy_capabilities",
    "telemetry_remote_stats",
    "triage_findings",
    "update_check",
  )

internal val priorityStrictToolNames =
  listOf(
    "feature_verify_started",
    "feature_verify_finished",
    "quality_check_started",
    "quality_check_finished",
    "pr_description_generated",
    "import_review",
    "triage_findings",
    "resolve_learnings",
    "add_learning",
    "feature_verify_workflow_open",
    "feature_verify_workflow_update",
    "feature_verify_workflow_get",
    "feature_verify_workflow_list",
    "feature_verify_workflow_latest",
    "feature_verify_workflow_resume",
    "feature_verify_workflow_continue",
    "new_skill_scaffold",
  )

internal val verifyLifecycleToolNames =
  listOf(
    "feature_verify_started",
    "feature_verify_finished",
    "feature_verify_workflow_get",
    "feature_verify_workflow_latest",
    "feature_verify_workflow_list",
    "feature_verify_workflow_continue",
    "feature_verify_workflow_open",
    "feature_verify_workflow_resume",
    "feature_verify_workflow_update",
  )

internal val removedToolNames =
  listOf(
    "feature_task_continuation_lookup",
    "feature_task_runtime_started",
    "feature_task_runtime_finished",
    "feature_task_runtime_stats",
    "feature_task_runtime_workflow_get",
    "feature_task_runtime_workflow_latest",
    "feature_task_runtime_workflow_list",
    "feature_task_runtime_workflow_continue",
    "feature_task_runtime_workflow_open",
    "feature_task_runtime_workflow_resume",
    "feature_task_runtime_workflow_update",
    "readian_auth_status",
    "readian_get_article",
    "readian_get_articles_for_topic_query",
    "readian_get_spotlight",
    "readian_mark_story_status",
    "readian_save_candidate",
    "feature_task_prose_started",
    "feature_task_prose_finished",
    "feature_task_prose_stats",
    "feature_task_prose_workflow_get",
    "feature_task_prose_workflow_latest",
    "feature_task_prose_workflow_list",
    "feature_task_prose_workflow_continue",
    "feature_task_prose_workflow_open",
    "feature_task_prose_workflow_resume",
    "feature_task_prose_workflow_update",
    "feature_implement_started",
    "feature_implement_finished",
    "feature_implement_stats",
    "feature_implement_workflow_get",
    "feature_implement_workflow_latest",
    "feature_implement_workflow_list",
    "feature_implement_workflow_continue",
    "feature_implement_workflow_open",
    "feature_implement_workflow_resume",
    "feature_implement_workflow_update",
    "goal_prose_started",
    "goal_prose_subtask_finished",
    "goal_prose_finished",
  )

private val schemaOnlyComponent: McpComponent by lazy {
  McpRuntimeContext(
    environment = disabledTelemetryEnvironment(Files.createTempDirectory("skillbill-mcp-tools-list")),
  ).mcpComponent()
}

internal fun toolsList(): List<*> {
  val response =
    decodeResponse(
      McpStdioServer.handleLine(
        """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
        schemaOnlyComponent,
      ),
    )
  return response.fieldMap("result")["tools"] as List<*>
}

internal fun McpRuntimeContext.callTool(
  name: String,
  arguments: Map<String, Any?> = emptyMap(),
): Map<String, Any?> =
  decodeResponse(McpStdioServer.handleLine(toolCallRequest(1, name, arguments), mcpComponent()))
    .fieldMap("result")

internal fun McpRuntimeContext.callToolPayload(
  name: String,
  arguments: Map<String, Any?> = emptyMap(),
): Map<String, Any?> {
  val result = callTool(name, arguments)
  val payload = toolPayload(result)
  assertEquals(false, result["isError"], "Expected $name to succeed but got $payload")
  return payload
}

internal fun McpRuntimeContext.callToolError(
  name: String,
  arguments: Map<String, Any?> = emptyMap(),
): Map<String, Any?> {
  val result = callTool(name, arguments)
  val payload = toolPayload(result)
  assertEquals(true, result["isError"], "Expected $name to fail but got $payload")
  return payload
}

internal fun List<*>.schemaFor(toolName: String): Map<String, Any?> {
  val tool = first { item -> JsonCodec.anyToStringAnyMap(item)?.get("name") == toolName }
  return requireNotNull(JsonCodec.anyToStringAnyMap(tool)?.get("inputSchema")).let { schema ->
    requireNotNull(JsonCodec.anyToStringAnyMap(schema))
  }
}

internal fun List<*>.toolNamedOrNull(toolName: String): Map<String, Any?>? =
  firstOrNull { item -> JsonCodec.anyToStringAnyMap(item)?.get("name") == toolName }
    ?.let { JsonCodec.anyToStringAnyMap(it) }

internal fun List<*>.descriptionFor(toolName: String): String =
  requireNotNull(toolNamedOrNull(toolName))["description"].toString()

internal fun Map<String, Any?>.properties(): Map<String, Any?> =
  requireNotNull(JsonCodec.anyToStringAnyMap(this["properties"]))

internal fun Map<String, Any?>.assertRequired(vararg names: String) {
  val required = this["required"] as List<*>
  names.forEach { name -> assertContains(required, name) }
}

internal fun Map<String, Any?>.enumFor(propertyName: String): List<*> {
  val property = requireNotNull(JsonCodec.anyToStringAnyMap(this[propertyName]))
  return requireNotNull(property["enum"] as? List<*>)
}

internal fun toolCallRequest(
  id: Int,
  name: String,
  arguments: Map<String, Any?>,
): String =
  JsonCodec.mapToJsonString(
    mapOf(
      "jsonrpc" to "2.0",
      "id" to id,
      "method" to "tools/call",
      "params" to
        mapOf(
          "name" to name,
          "arguments" to arguments,
        ),
    ),
  )

internal fun toolPayload(result: Map<String, Any?>): Map<String, Any?> {
  val content = result["content"] as List<*>
  val textContent = requireNotNull(JsonCodec.anyToStringAnyMap(content.first()))
  return decodeJsonObject(textContent["text"].toString())
}

internal fun decodeToolArguments(rawJson: String): Map<String, Any?> {
  val request = decodeJsonObject(rawJson)
  val params = requireNotNull(JsonCodec.anyToStringAnyMap(request["params"]))
  return requireNotNull(JsonCodec.anyToStringAnyMap(params["arguments"]))
}

internal fun decodeResponse(rawJson: String?): Map<String, Any?> {
  assertNotNull(rawJson)
  return decodeJsonObject(rawJson)
}

internal fun Map<String, Any?>.fieldMap(name: String): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(this[name]).orEmpty()
