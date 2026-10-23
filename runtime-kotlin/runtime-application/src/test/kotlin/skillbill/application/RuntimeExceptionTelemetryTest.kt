package skillbill.application

import skillbill.application.telemetry.service.REDACTED_ERROR_MESSAGE
import skillbill.application.telemetry.service.enqueueRuntimeException
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.ports.telemetry.model.TelemetryCorrelationStatus
import skillbill.ports.telemetry.model.TelemetryOutboxClaimRequest
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.ports.telemetry.model.TelemetryOutboxSettlementResult
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeExceptionTelemetryTest {
  @Test
  fun `enqueueRuntimeException enqueues exactly one exception event`() {
    val captured = mutableListOf<Pair<String, String>>()
    val outbox = capturingOutbox(captured)

    enqueueRuntimeException(outbox, "my_tool", RuntimeException("something went wrong"), "full")

    assertEquals(1, captured.size)
    assertEquals(TelemetryOutboxEvent.RUNTIME_EXCEPTION.wireValue, captured[0].first)
  }

  @Test
  fun `enqueueRuntimeException includes workflow_phase error_type and error_message`() {
    val captured = mutableListOf<Pair<String, String>>()
    val outbox = capturingOutbox(captured)
    enqueueRuntimeException(outbox, "feature_implement_workflow_open", IllegalStateException("bad state"), "full")

    val payload = captured[0].second
    assert(payload.contains("feature_implement_workflow_open")) { "payload must contain workflow_phase" }
    assert(payload.contains("IllegalStateException")) { "payload must contain error_type" }
    assert(payload.contains("bad state")) { "payload must contain error_message" }
  }

  @Test
  fun `enqueueRuntimeException does not include file paths or home directory in stack trace`() {
    val captured = mutableListOf<Pair<String, String>>()
    enqueueRuntimeException(capturingOutbox(captured), "test_tool", RuntimeException("fail"), "full")

    val payload = captured[0].second
    assertFalse(payload.contains("/home/"), "stack trace must not contain /home/ paths")
    assertFalse(payload.contains("\\Users\\"), "stack trace must not contain \\Users\\ paths")
  }

  @Test
  fun `anonymous level drops every fragment of the caller supplied message`() {
    val captured = mutableListOf<Pair<String, String>>()
    val message = "failed reading /home/dev/projects/SKILL-163/spec.md for SKILL-163"

    enqueueRuntimeException(capturingOutbox(captured), "test_tool", IllegalStateException(message), "anonymous")

    val payload = captured[0].second
    assertFalse(payload.contains("SKILL-163"), "tracker key must not survive at anonymous")
    assertFalse(payload.contains("/home/dev"), "file path must not survive at anonymous")
    assertFalse(payload.contains("failed reading"), "message prose must not survive at anonymous")
    assertContains(payload, REDACTED_ERROR_MESSAGE)
  }

  @Test
  fun `anonymous level retains error_type and workflow_phase`() {
    val captured = mutableListOf<Pair<String, String>>()

    enqueueRuntimeException(capturingOutbox(captured), "goal_workflow_open", IllegalStateException("x"), "anonymous")

    val payload = captured[0].second
    assertContains(payload, "goal_workflow_open")
    assertContains(payload, "IllegalStateException")
  }

  @Test
  fun `anonymous level keeps only skillbill frames and full level keeps foreign frames`() {
    val error =
      IllegalStateException("boom").apply {
        stackTrace =
          arrayOf(
            StackTraceElement("skillbill.application.telemetry.TelemetryService", "captureException", "x.kt", 11),
            StackTraceElement("java.util.concurrent.ThreadPoolExecutor", "runWorker", "y.java", 22),
            StackTraceElement("com.thirdparty.Widget", "render", "z.java", 33),
            StackTraceElement("skillbill.infrastructure.sqlite.telemetry.TelemetryOutboxStore", "enqueue", "w.kt", 44),
          )
      }

    val anonymous = mutableListOf<Pair<String, String>>()
    enqueueRuntimeException(capturingOutbox(anonymous), "test_tool", error, "anonymous")
    val anonymousPayload = anonymous[0].second
    assertContains(anonymousPayload, "skillbill.application.telemetry.TelemetryService.captureException")
    assertContains(anonymousPayload, "skillbill.infrastructure.sqlite.telemetry.TelemetryOutboxStore.enqueue")
    assertFalse(anonymousPayload.contains("java.util.concurrent"), "foreign frames must be dropped")
    assertFalse(anonymousPayload.contains("com.thirdparty"), "foreign frames must be dropped")

    val full = mutableListOf<Pair<String, String>>()
    enqueueRuntimeException(capturingOutbox(full), "test_tool", error, "full")
    val fullPayload = full[0].second
    assertTrue(fullPayload.contains("java.util.concurrent"), "full level keeps foreign frames")
    assertTrue(fullPayload.contains("com.thirdparty"), "full level keeps foreign frames")
  }

  @Test
  fun `an unresolved level falls back to the redacted branch`() {
    val captured = mutableListOf<Pair<String, String>>()

    enqueueRuntimeException(capturingOutbox(captured), "test_tool", IllegalStateException("SKILL-163 leaked"), "")

    assertFalse(captured[0].second.contains("SKILL-163"), "an unknown level must redact")
  }

  private fun capturingOutbox(captured: MutableList<Pair<String, String>>): TelemetryOutboxRepository =
    object : TelemetryOutboxRepository {
      override fun enqueue(
        event: TelemetryOutboxEvent,
        payloadJson: String,
      ): Long {
        captured.add(event.wireValue to payloadJson)
        return captured.size.toLong()
      }

      override fun claimPending(request: TelemetryOutboxClaimRequest): List<TelemetryOutboxRecord> = emptyList()

      override fun pendingCount(): Int = 0

      override fun blockedCount(attemptBudget: Int): Int = 0

      override fun latestError(): String? = null

      override fun lastSyncedAt(): String? = null

      override fun latestByCorrelation(
        events: Set<TelemetryOutboxEvent>,
        incidentToken: String,
      ): TelemetryCorrelationStatus? = null

      override fun markSynced(
        eventIds: List<Long>,
        claimToken: String,
      ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

      override fun markFailed(
        eventIds: List<Long>,
        claimToken: String,
        lastError: String,
      ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

      override fun markUnconfirmed(
        eventIds: List<Long>,
        claimToken: String,
        lastError: String,
      ): TelemetryOutboxSettlementResult = TelemetryOutboxSettlementResult.forRequest(eventIds, updatedRows = 0)

      override fun clear(): Int = 0
    }
}
