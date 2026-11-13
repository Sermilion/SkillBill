package skillbill.mcp.scaffold

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.install.model.InstallApplyIssue
import skillbill.mcp.shared.McpToolPayloadKeys
import skillbill.scaffold.model.ScaffoldResult

internal fun scaffoldResultMap(
  sessionId: String,
  payload: Map<String, Any?>,
  result: ScaffoldResult,
  dryRun: Boolean,
  orchestrated: Boolean,
): Map<String, Any?> {
  if (result.installIssues.isNotEmpty()) {
    val summary = "Scaffold was rolled back after skill installation failed."
    val issues = result.installIssues.map(::installIssuePayload)
    return if (orchestrated) {
      mapOf(
        LifecycleTelemetryPayloadKeys.MODE to "orchestrated",
        LifecycleTelemetryPayloadKeys.TELEMETRY_PAYLOAD to
          scaffoldTelemetryPayload(payload, result.skillName) +
          mapOf(
            LifecycleTelemetryPayloadKeys.RESULT to "failed",
            LifecycleTelemetryPayloadKeys.ERROR to summary,
          ),
        LifecycleTelemetryPayloadKeys.ERROR to summary,
        McpScaffoldPayloadKeys.INSTALL_ISSUES to issues,
        McpToolPayloadKeys.IS_ERROR to true,
        "skill_path" to result.skillPath.toString(),
        "notes" to result.notes,
      )
    } else {
      mapOf(
        SharedPayloadKeys.STATUS to "error",
        LifecycleTelemetryPayloadKeys.SESSION_ID to sessionId,
        LifecycleTelemetryPayloadKeys.ERROR to summary,
        McpScaffoldPayloadKeys.INSTALL_ISSUES to issues,
        McpToolPayloadKeys.IS_ERROR to true,
      )
    }
  }

  val outcome = if (dryRun) "dry-run" else "success"
  val baseTelemetryPayload =
    scaffoldTelemetryPayload(payload, result.skillName) +
      mapOf(LifecycleTelemetryPayloadKeys.RESULT to outcome)
  return if (orchestrated) {
    mapOf(
      LifecycleTelemetryPayloadKeys.MODE to "orchestrated",
      LifecycleTelemetryPayloadKeys.TELEMETRY_PAYLOAD to baseTelemetryPayload,
      "skill_path" to result.skillPath.toString(),
      "notes" to result.notes,
    )
  } else {
    mapOf(
      SharedPayloadKeys.STATUS to "ok",
      LifecycleTelemetryPayloadKeys.SESSION_ID to sessionId,
      "skill_path" to result.skillPath.toString(),
      "notes" to result.notes,
    )
  }
}

internal fun scaffoldFailureMap(
  sessionId: String,
  payload: Map<String, Any?>,
  orchestrated: Boolean,
  error: Throwable,
): Map<String, Any?> =
  if (orchestrated) {
    mapOf(
      LifecycleTelemetryPayloadKeys.MODE to "orchestrated",
      LifecycleTelemetryPayloadKeys.TELEMETRY_PAYLOAD to
        scaffoldTelemetryPayload(payload, payload["name"].orEmpty()) +
        mapOf(
          LifecycleTelemetryPayloadKeys.RESULT to "failed",
          LifecycleTelemetryPayloadKeys.ERROR to error.message.orEmpty(),
        ),
      LifecycleTelemetryPayloadKeys.ERROR to error.message.orEmpty(),
    )
  } else {
    mapOf(
      SharedPayloadKeys.STATUS to "error",
      LifecycleTelemetryPayloadKeys.SESSION_ID to sessionId,
      LifecycleTelemetryPayloadKeys.ERROR to error.message.orEmpty(),
    )
  }

private fun scaffoldTelemetryPayload(
  payload: Map<String, Any?>,
  skillName: String,
): Map<String, Any?> =
  mapOf(
    "kind" to payload["kind"].orEmpty(),
    "skill_name" to skillName,
    "platform" to payload["platform"].orEmpty(),
    "family" to payload["family"].orEmpty(),
    "area" to payload["area"].orEmpty(),
    LifecycleTelemetryPayloadKeys.SKILL to "skill-bill-scaffold",
  )

private fun installIssuePayload(issue: InstallApplyIssue): Map<String, Any?> =
  buildMap {
    put(McpScaffoldPayloadKeys.KIND, issue.kind.name.lowercase())
    put(McpScaffoldPayloadKeys.MESSAGE, issue.message)
    issue.skillName?.let { put(McpScaffoldPayloadKeys.SKILL_NAME, it) }
    issue.agent?.let { put(McpScaffoldPayloadKeys.AGENT, it.wireValue) }
    issue.path?.let { put(McpScaffoldPayloadKeys.PATH, it.toString()) }
    issue.guidance?.let { put(McpScaffoldPayloadKeys.GUIDANCE, it) }
    issue.causeClass?.let { put(McpScaffoldPayloadKeys.CAUSE_CLASS, it) }
  }

private fun Any?.orEmpty(): String = this as? String ?: ""
