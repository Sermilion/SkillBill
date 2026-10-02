package skillbill.application.telemetry.service

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.error.core.failureCodeLabel
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository

internal const val REDACTED_ERROR_MESSAGE = "[redacted]"

private const val MAX_STACK_FRAMES = 12
private const val MAX_MESSAGE_LENGTH = 512
private const val SKILLBILL_FRAME_PREFIX = "skillbill."

fun enqueueRuntimeException(
  outbox: TelemetryOutboxRepository,
  workflowPhase: String,
  error: Exception,
  level: String,
) {
  val unredacted = level == "full"
  val payload =
    mapOf(
      "workflow_phase" to workflowPhase,
      "error_type" to (error.failureCodeLabel() ?: error.javaClass.name.substringAfterLast('.')),
      "error_message" to if (unredacted) error.message.orEmpty().take(MAX_MESSAGE_LENGTH) else REDACTED_ERROR_MESSAGE,
      "stack_trace" to redactedStackTrace(error, unredacted),
    )
  outbox.enqueue(TelemetryOutboxEvent.RUNTIME_EXCEPTION, JsonCodec.mapToJsonString(payload))
}

private fun redactedStackTrace(
  error: Exception,
  unredacted: Boolean,
): String =
  error.stackTrace
    .filter { unredacted || it.className.startsWith(SKILLBILL_FRAME_PREFIX) }
    .take(MAX_STACK_FRAMES)
    .joinToString("\n") { frame ->
      "${frame.className}.${frame.methodName}:${frame.lineNumber}"
    }
