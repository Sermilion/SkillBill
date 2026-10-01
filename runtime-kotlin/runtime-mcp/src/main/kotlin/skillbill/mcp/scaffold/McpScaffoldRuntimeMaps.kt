package skillbill.mcp.scaffold

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.scaffold.model.ScaffoldResult

internal fun scaffoldSuccessMap(
  sessionId: String,
  payload: Map<String, Any?>,
  result: ScaffoldResult,
  dryRun: Boolean,
  orchestrated: Boolean,
): Map<String, Any?> {
  val outcome = if (dryRun) "dry-run" else "success"
  val baseTelemetryPayload =
    mapOf(
      "kind" to result.kind,
      "skill_name" to result.skillName,
      "platform" to payload["platform"].orEmpty(),
      "family" to payload["family"].orEmpty(),
      "area" to payload["area"].orEmpty(),
      LifecycleTelemetryPayloadKeys.RESULT to outcome,
      LifecycleTelemetryPayloadKeys.SKILL to "skill-bill-scaffold",
    )
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
        mapOf(
          "kind" to payload["kind"].orEmpty(),
          "skill_name" to payload["name"].orEmpty(),
          "platform" to payload["platform"].orEmpty(),
          "family" to payload["family"].orEmpty(),
          "area" to payload["area"].orEmpty(),
          LifecycleTelemetryPayloadKeys.RESULT to "failed",
          LifecycleTelemetryPayloadKeys.SKILL to "skill-bill-scaffold",
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

private fun Any?.orEmpty(): String = this as? String ?: ""
