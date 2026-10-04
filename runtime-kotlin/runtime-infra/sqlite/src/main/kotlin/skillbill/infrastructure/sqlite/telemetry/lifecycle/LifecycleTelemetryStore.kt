package skillbill.infrastructure.sqlite.telemetry.lifecycle

import skillbill.contracts.telemetry.TelemetryOutboxEvent
import skillbill.infrastructure.sqlite.telemetry.lifecycle.session.LifecycleTelemetryFeatureTaskSessionAdapter
import skillbill.infrastructure.sqlite.telemetry.lifecycle.session.LifecycleTelemetryFeatureVerifySessionAdapter
import skillbill.infrastructure.sqlite.telemetry.lifecycle.session.LifecycleTelemetryGoalSessionAdapter
import skillbill.infrastructure.sqlite.telemetry.lifecycle.session.LifecycleTelemetryMeasurementAdapter
import skillbill.infrastructure.sqlite.telemetry.lifecycle.session.LifecycleTelemetryQualityCheckSessionAdapter
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.telemetry.lifecycle.FeatureTaskRuntimeLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.FeatureTaskRuntimeTelemetryMeasurementRepository
import skillbill.ports.telemetry.lifecycle.FeatureVerifyLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.GoalLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.PrDescriptionLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.QualityCheckLifecycleTelemetryRepository
import skillbill.ports.telemetry.lifecycle.ReviewStageTelemetryMeasurementRepository
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import java.sql.Connection

internal class LifecycleTelemetryStore private constructor(
  adapters: LifecycleTelemetryStoreAdapters,
) : LifecycleTelemetryRepository,
  FeatureTaskRuntimeTelemetryMeasurementRepository by adapters.measurements,
  ReviewStageTelemetryMeasurementRepository by adapters.measurements,
  FeatureTaskRuntimeLifecycleTelemetryRepository by adapters.featureTaskSessions,
  QualityCheckLifecycleTelemetryRepository by adapters.qualityCheckSessions,
  FeatureVerifyLifecycleTelemetryRepository by adapters.featureVerifySessions,
  PrDescriptionLifecycleTelemetryRepository by adapters.prDescriptionSessions,
  GoalLifecycleTelemetryRepository by adapters.goalSessions {
  companion object {
    operator fun invoke(
      connection: Connection,
      runtimeVersion: String,
      diagnostics: RuntimeDiagnostics,
    ): LifecycleTelemetryStore =
      LifecycleTelemetryStore(LifecycleTelemetryStoreAdapters(connection, runtimeVersion, diagnostics))
  }
}

internal class LifecycleTelemetryStoreAdapters(
  connection: Connection,
  runtimeVersion: String,
  diagnostics: RuntimeDiagnostics,
) {
  val measurements = LifecycleTelemetryMeasurementAdapter(connection, runtimeVersion)
  val featureTaskSessions = LifecycleTelemetryFeatureTaskSessionAdapter(connection, runtimeVersion, diagnostics)
  val qualityCheckSessions = LifecycleTelemetryQualityCheckSessionAdapter(connection, runtimeVersion, diagnostics)
  val featureVerifySessions = LifecycleTelemetryFeatureVerifySessionAdapter(connection, runtimeVersion, diagnostics)
  val prDescriptionSessions = LifecycleTelemetryPrDescriptionSessionAdapter(connection, runtimeVersion)
  val goalSessions = LifecycleTelemetryGoalSessionAdapter(connection, runtimeVersion, diagnostics)
}

internal class LifecycleTelemetryPrDescriptionSessionAdapter(
  private val connection: Connection,
  private val runtimeVersion: String,
) : PrDescriptionLifecycleTelemetryRepository {
  override fun prDescriptionGenerated(
    record: PrDescriptionGeneratedRecord,
    level: String,
  ) {
    enqueueTelemetry(
      connection,
      runtimeVersion,
      TelemetryOutboxEvent.PR_DESCRIPTION_GENERATED,
      prDescriptionPayload(record, level),
    )
  }
}
