package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import java.sql.Connection

internal fun saveFeatureVerifyStarted(
  connection: Connection,
  record: FeatureVerifyStartedRecord,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_verify_sessions (
      session_id, acceptance_criteria_count, rollout_relevant, spec_summary, started_at
    ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(
      record.sessionId,
      record.acceptanceCriteriaCount,
      record.rolloutRelevant.toSqlInt(),
      record.specSummary,
    )
    statement.executeUpdate()
  }
}

internal enum class TerminalSaveOutcome { FIRST_TERMINAL, DUPLICATE }

internal fun saveFeatureVerifyFinished(
  connection: Connection,
  record: FeatureVerifyFinishedRecord,
): TerminalSaveOutcome {
  val gapsFoundJson = listJson(record.gapsFound)
  if (rowExists(connection, "feature_verify_sessions", record.sessionId)) {
    if (lifecycleAlreadyFinished(connection, "feature_verify_sessions", record.sessionId)) {
      incrementDuplicateTerminalFinishedEvents(connection, "feature_verify_sessions", record.sessionId)
      return TerminalSaveOutcome.DUPLICATE
    }
    updateFeatureVerifyFinished(connection, record, gapsFoundJson)
  } else {
    insertFeatureVerifyFinished(connection, record, gapsFoundJson)
  }
  return TerminalSaveOutcome.FIRST_TERMINAL
}

private fun updateFeatureVerifyFinished(
  connection: Connection,
  record: FeatureVerifyFinishedRecord,
  gapsFoundJson: String,
) {
  connection.prepareStatement(
    """
    UPDATE feature_verify_sessions SET
      feature_flag_audit_performed = ?,
      review_iterations = ?,
      audit_result = ?,
      completion_status = ?,
      history_relevance = ?,
      history_helpfulness = ?,
      gaps_found = ?,
      qa_outcome = ?,
      qa_reason = ?,
      finished_at = CURRENT_TIMESTAMP
    WHERE session_id = ?
      AND (finished_event_emitted_at IS NULL OR completion_status = 'stale')
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(featureVerifyFinishedValues(record, gapsFoundJson, includeSessionFirst = false))
    statement.executeUpdate()
  }
}

private fun insertFeatureVerifyFinished(
  connection: Connection,
  record: FeatureVerifyFinishedRecord,
  gapsFoundJson: String,
) {
  connection.prepareStatement(
    """
    INSERT INTO feature_verify_sessions (
      session_id, feature_flag_audit_performed, review_iterations,
      audit_result, completion_status, history_relevance,
      history_helpfulness, gaps_found, qa_outcome, qa_reason, finished_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
    """.trimIndent(),
  ).use { statement ->
    statement.bindAll(featureVerifyFinishedValues(record, gapsFoundJson, includeSessionFirst = true))
    statement.executeUpdate()
  }
}
