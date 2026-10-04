package skillbill.mcp.review

import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.learning.LearningPayloadKeys
import skillbill.contracts.learning.NO_APPLIED_LEARNINGS
import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys

internal data class McpReviewImportSkippedContract(
  val reason: String,
  val reviewRunId: String?,
  val findingCount: Int,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.STATUS to "skipped",
      UpdateCheckPayloadKeys.REASON to reason,
      ReviewVerificationSignalKeys.REVIEW_RUN_ID to reviewRunId,
      ReviewFinishedTelemetryPayloadKeys.FINDING_COUNT to findingCount,
    )
}

internal data class McpTriageSkippedContract(
  val reason: String,
  val reviewRunId: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.STATUS to "skipped",
      UpdateCheckPayloadKeys.REASON to reason,
      ReviewVerificationSignalKeys.REVIEW_RUN_ID to reviewRunId,
    )
}

internal data class McpLearningsSkippedContract(
  val reason: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.STATUS to "skipped",
      UpdateCheckPayloadKeys.REASON to reason,
      LearningPayloadKeys.APPLIED_LEARNINGS to NO_APPLIED_LEARNINGS,
      LearningPayloadKeys.LEARNINGS to emptyList<Any>(),
    )
}

internal data class McpOrchestratedPayloadContract(
  val basePayload: Map<String, Any?>,
  val telemetryPayload: Map<String, Any?>?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply {
      putAll(basePayload)
      put(LifecycleTelemetryPayloadKeys.MODE, "orchestrated")
      telemetryPayload?.let { telemetry ->
        put(
          LifecycleTelemetryPayloadKeys.TELEMETRY_PAYLOAD,
          linkedMapOf<String, Any?>().apply {
            putAll(telemetry)
            put(LifecycleTelemetryPayloadKeys.SKILL, "bill-code-review")
          },
        )
      }
    }
}
