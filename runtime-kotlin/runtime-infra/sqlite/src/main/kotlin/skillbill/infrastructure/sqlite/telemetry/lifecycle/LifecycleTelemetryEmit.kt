package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.telemetry.outbox.TelemetryOutboxStore
import skillbill.infrastructure.sqlite.telemetry.redaction.telemetryRedactionSalt
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.model.ReviewStageDegradationMeasurement
import java.sql.Connection

internal fun emitFeatureTaskRuntimeStarted(
  connection: Connection,
  runtimeVersion: String,
  sessionId: String,
  level: String,
) {
  val row = lifecycleRow(connection, "feature_task_runtime_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, runtimeVersion, row, "feature_task_runtime_sessions", "started_event_emitted_at"),
    TelemetryOutboxEvent.FEATURE_TASK_RUNTIME_STARTED,
  ) { featureTaskRuntimeStartedPayload(row, level, telemetryRedactionSalt(connection)) }
}

internal fun emitFeatureTaskRuntimeFinished(
  connection: Connection,
  runtimeVersion: String,
  sessionId: String,
  level: String,
  diagnostics: RuntimeDiagnostics,
) {
  val row = lifecycleRow(connection, "feature_task_runtime_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, runtimeVersion, row, "feature_task_runtime_sessions", "finished_event_emitted_at"),
    TelemetryOutboxEvent.FEATURE_TASK_RUNTIME_FINISHED,
  ) {
    featureTaskRuntimeFinishedPayload(
      row,
      level,
      telemetryRedactionSalt(connection),
      diagnostics,
    )
  }
}

internal fun emitQualityCheckStarted(
  connection: Connection,
  runtimeVersion: String,
  sessionId: String,
) {
  val row = lifecycleRow(connection, "quality_check_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, runtimeVersion, row, "quality_check_sessions", "started_event_emitted_at"),
    TelemetryOutboxEvent.QUALITY_CHECK_STARTED,
  ) { qualityCheckStartedPayload(row) }
}

internal fun emitQualityCheckFinished(
  connection: Connection,
  runtimeVersion: String,
  sessionId: String,
  level: String,
  diagnostics: RuntimeDiagnostics,
) {
  val row = lifecycleRow(connection, "quality_check_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, runtimeVersion, row, "quality_check_sessions", "finished_event_emitted_at"),
    TelemetryOutboxEvent.QUALITY_CHECK_FINISHED,
  ) { qualityCheckFinishedPayload(row, level, diagnostics) }
}

internal fun emitFeatureVerifyStarted(
  connection: Connection,
  runtimeVersion: String,
  sessionId: String,
  level: String,
) {
  val row = lifecycleRow(connection, "feature_verify_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, runtimeVersion, row, "feature_verify_sessions", "started_event_emitted_at"),
    TelemetryOutboxEvent.FEATURE_VERIFY_STARTED,
  ) { featureVerifyStartedPayload(row, level) }
}

internal fun emitFeatureVerifyFinished(
  connection: Connection,
  runtimeVersion: String,
  sessionId: String,
  level: String,
  diagnostics: RuntimeDiagnostics,
) {
  val row = lifecycleRow(connection, "feature_verify_sessions", sessionId) ?: return
  emitOnce(
    LifecycleEmitRequest(connection, runtimeVersion, row, "feature_verify_sessions", "finished_event_emitted_at"),
    TelemetryOutboxEvent.FEATURE_VERIFY_FINISHED,
  ) { featureVerifyFinishedPayload(row, level, diagnostics) }
}

internal fun enqueueTelemetry(
  connection: Connection,
  runtimeVersion: String,
  event: TelemetryOutboxEvent,
  payload: Map<String, Any?>,
) {
  TelemetryOutboxStore(connection, runtimeVersion).enqueue(event, JsonCodec.mapToJsonString(payload))
}

internal fun reviewStageDegradationExists(
  connection: Connection,
  record: ReviewStageDegradationMeasurement,
): Boolean =
  connection.prepareStatement(
    """
    SELECT 1 FROM telemetry_outbox
    WHERE event_name = ?
      AND json_extract(payload_json, '$.review_run_id') = ?
      AND json_extract(payload_json, '$.seam') = ?
      AND json_extract(payload_json, '$.reason') = ?
    LIMIT 1
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      TelemetryOutboxEvent.REVIEW_STAGE_DEGRADATION.wireValue,
      record.reviewRunId,
      record.seam,
      record.reason.wireValue,
    )
    statement.executeQuery().use { it.next() }
  }

private data class LifecycleEmitRequest(
  internal val connection: Connection,
  internal val runtimeVersion: String,
  internal val row: Map<String, Any?>,
  internal val tableName: String,
  internal val emittedColumn: String,
)

private fun emitOnce(
  request: LifecycleEmitRequest,
  event: TelemetryOutboxEvent,
  payload: () -> Map<String, Any?>,
) {
  if (request.row.stringOrEmpty(request.emittedColumn).isNotBlank()) {
    return
  }
  enqueueTelemetry(request.connection, request.runtimeVersion, event, payload())
  markLifecycleEmitted(
    request.connection,
    request.tableName,
    request.emittedColumn,
    request.row.stringOrEmpty(LifecycleTelemetryPayloadKeys.SESSION_ID),
  )
}
