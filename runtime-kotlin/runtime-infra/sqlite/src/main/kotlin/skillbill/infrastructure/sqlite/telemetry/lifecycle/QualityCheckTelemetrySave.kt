package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import java.sql.Connection

internal fun saveQualityCheckStarted(
  connection: Connection,
  record: QualityCheckStartedRecord,
) {
  connection.prepareStatement(
    """
    INSERT INTO quality_check_sessions (
      session_id, routed_skill, detected_stack, fallback, fallback_reason, scope_type, initial_failure_count, started_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.sessionId,
      record.routedSkill,
      record.detectedStack,
      record.fallback.toSqlInt(),
      record.fallbackReason,
      record.scopeType,
      record.initialFailureCount,
    )
    statement.executeUpdate()
  }
}

internal fun saveQualityCheckFinished(
  connection: Connection,
  record: QualityCheckFinishedRecord,
): TerminalSaveOutcome {
  val failingCheckNamesJson = listJson(record.failingCheckNames)
  if (rowExists(connection, "quality_check_sessions", record.sessionId)) {
    if (lifecycleAlreadyFinished(connection, "quality_check_sessions", record.sessionId)) {
      incrementDuplicateTerminalFinishedEvents(connection, "quality_check_sessions", record.sessionId)
      return TerminalSaveOutcome.DUPLICATE
    }
    updateQualityCheckFinished(connection, record, failingCheckNamesJson)
  } else {
    insertQualityCheckFinished(connection, record, failingCheckNamesJson)
  }
  return TerminalSaveOutcome.FIRST_TERMINAL
}

private fun updateQualityCheckFinished(
  connection: Connection,
  record: QualityCheckFinishedRecord,
  failingCheckNamesJson: String,
) {
  connection.prepareStatement(
    """
    UPDATE quality_check_sessions SET
      routed_skill = ?,
      detected_stack = ?,
      fallback = ?,
      fallback_reason = ?,
      scope_type = ?,
      initial_failure_count = ?,
      final_failure_count = ?,
      iterations = ?,
      result = ?,
      failing_check_names = ?,
      unsupported_reason = ?,
      finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
      AND (finished_event_emitted_at IS NULL OR result = 'stale')
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.routedSkill,
      record.detectedStack,
      record.fallback.toSqlInt(),
      record.fallbackReason,
      record.scopeType,
      record.initialFailureCount,
      record.finalFailureCount,
      record.iterations,
      record.result,
      failingCheckNamesJson,
      record.unsupportedReason,
      record.sessionId,
    )
    statement.executeUpdate()
  }
}

private fun insertQualityCheckFinished(
  connection: Connection,
  record: QualityCheckFinishedRecord,
  failingCheckNamesJson: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO quality_check_sessions (
      session_id, routed_skill, detected_stack, fallback, fallback_reason,
      scope_type, initial_failure_count, final_failure_count, iterations,
      result, failing_check_names, unsupported_reason, finished_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.sessionId,
      record.routedSkill,
      record.detectedStack,
      record.fallback.toSqlInt(),
      record.fallbackReason,
      record.scopeType,
      record.initialFailureCount,
      record.finalFailureCount,
      record.iterations,
      record.result,
      failingCheckNamesJson,
      record.unsupportedReason,
    )
    statement.executeUpdate()
  }
}
