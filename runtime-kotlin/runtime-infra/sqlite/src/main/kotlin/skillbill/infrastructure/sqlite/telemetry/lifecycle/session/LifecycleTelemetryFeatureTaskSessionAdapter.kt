package skillbill.infrastructure.sqlite.telemetry.lifecycle.session

import skillbill.infrastructure.sqlite.telemetry.lifecycle.TerminalSaveOutcome
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitFeatureTaskRuntimeFinished
import skillbill.infrastructure.sqlite.telemetry.lifecycle.emitFeatureTaskRuntimeStarted
import skillbill.infrastructure.sqlite.telemetry.lifecycle.saveFeatureTaskRuntimeFinished
import skillbill.infrastructure.sqlite.telemetry.lifecycle.saveFeatureTaskRuntimeStarted
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.FeatureTaskRuntimeLifecycleTelemetryRepository
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import java.sql.Connection

internal class LifecycleTelemetryFeatureTaskSessionAdapter(
  private val connection: Connection,
  private val runtimeVersion: String,
  private val diagnostics: RuntimeDiagnostics,
) : FeatureTaskRuntimeLifecycleTelemetryRepository {
  override fun featureTaskRuntimeStarted(
    record: FeatureTaskRuntimeStartedRecord,
    level: String,
  ) {
    saveFeatureTaskRuntimeStarted(connection, record)
    emitFeatureTaskRuntimeStarted(connection, runtimeVersion, record.sessionId, level)
  }

  override fun featureTaskRuntimeFinished(
    record: FeatureTaskRuntimeFinishedRecord,
    level: String,
  ) {
    if (saveFeatureTaskRuntimeFinished(connection, record) == TerminalSaveOutcome.FIRST_TERMINAL) {
      emitFeatureTaskRuntimeFinished(connection, runtimeVersion, record.sessionId, level, diagnostics)
    }
  }
}
