package skillbill.infrastructure.sqlite.telemetry.lifecycle.session

import skillbill.infrastructure.sqlite.telemetry.lifecycle.TerminalSaveOutcome
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitQualityCheckFinished
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitQualityCheckStarted
import skillbill.infrastructure.sqlite.telemetry.redaction.saveQualityCheckFinished
import skillbill.infrastructure.sqlite.telemetry.redaction.saveQualityCheckStarted
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.QualityCheckLifecycleTelemetryRepository
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import java.sql.Connection

internal class LifecycleTelemetryQualityCheckSessionAdapter(
  private val connection: Connection,
  private val runtimeVersion: String,
  private val diagnostics: RuntimeDiagnostics,
) : QualityCheckLifecycleTelemetryRepository {
  override fun qualityCheckStarted(
    record: QualityCheckStartedRecord,
    level: String,
  ) {
    saveQualityCheckStarted(connection, record)
    emitQualityCheckStarted(connection, runtimeVersion, record.sessionId)
  }

  override fun qualityCheckFinished(
    record: QualityCheckFinishedRecord,
    level: String,
  ) {
    if (saveQualityCheckFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitQualityCheckFinished(connection, runtimeVersion, record.sessionId, level, diagnostics)
    }
  }
}
