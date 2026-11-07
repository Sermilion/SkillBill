package skillbill.infrastructure.sqlite.telemetry.lifecycle.session

import skillbill.infrastructure.sqlite.telemetry.lifecycle.TerminalSaveOutcome
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitFeatureVerifyFinished
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitFeatureVerifyStarted
import skillbill.infrastructure.sqlite.telemetry.lifecycle.saveFeatureVerifyFinished
import skillbill.infrastructure.sqlite.telemetry.lifecycle.saveFeatureVerifyStarted
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.FeatureVerifyLifecycleTelemetryRepository
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import java.sql.Connection

internal class LifecycleTelemetryFeatureVerifySessionAdapter(
  private val connection: Connection,
  private val runtimeVersion: String,
  private val diagnostics: RuntimeDiagnostics,
) : FeatureVerifyLifecycleTelemetryRepository {
  override fun featureVerifyStarted(
    record: FeatureVerifyStartedRecord,
    level: String,
  ) {
    saveFeatureVerifyStarted(connection, record)
    emitFeatureVerifyStarted(connection, runtimeVersion, record.sessionId, level)
  }

  override fun featureVerifyFinished(
    record: FeatureVerifyFinishedRecord,
    level: String,
  ) {
    if (saveFeatureVerifyFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitFeatureVerifyFinished(connection, runtimeVersion, record.sessionId, level, diagnostics)
    }
  }

  override fun featureVerifyStaleReason(sessionId: String): String? {
    if (sessionId.isBlank()) return null
    return connection.prepareStatement("SELECT stale_reason FROM feature_verify_sessions WHERE session_id = ?").use {
      it.setString(1, sessionId)
      it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
    }
  }
}
