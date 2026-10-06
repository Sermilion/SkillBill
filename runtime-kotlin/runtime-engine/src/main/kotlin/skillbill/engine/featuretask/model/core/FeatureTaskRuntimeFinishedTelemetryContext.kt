package skillbill.engine.featuretask.model.core

import skillbill.application.telemetry.model.FeatureTaskRuntimeAgentContext
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability

const val PHASE_STRATEGIES_DISPATCH_JOIN_SEAM: String = "telemetry.phase_strategies.dispatch_join"

const val PHASE_STRATEGIES_DISPATCH_JOIN_EXPECTED: String = "admitted dispatch entry"

data class FeatureTaskRuntimePhaseStrategies(
  val availability: TelemetryMeasurementAvailability,
  val values: Map<String, Map<String, Any>>?,
  val missingPhaseIds: List<String> = emptyList(),
)

internal data class FeatureTaskRuntimeFinishedTelemetryContext(
  val telemetrySessionId: String,
  val phaseOutcomes: () -> Map<String, String>,
  val phaseStrategies: () -> FeatureTaskRuntimePhaseStrategies = {
    FeatureTaskRuntimePhaseStrategies(
      availability = TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE,
      values = null,
    )
  },
  val reviewFixIterationCount: () -> Int,
  val auditGapIterationCount: () -> Int? = { null },
  val agentContext: () -> FeatureTaskRuntimeAgentContext = { FeatureTaskRuntimeAgentContext() },
  val findingVerificationTelemetry: () -> FeatureTaskRuntimeFindingVerificationTelemetry = {
    FeatureTaskRuntimeFindingVerificationTelemetry()
  },
  val regenerationTelemetry: () -> FeatureTaskRuntimeRegenerationTelemetry = {
    FeatureTaskRuntimeRegenerationTelemetry()
  },
  val phaseTokenData: () -> Pair<String?, Int?> = { null to null },
  val crashReconciliation: () -> FeatureTaskRuntimeCrashReconciliationResult = {
    FeatureTaskRuntimeCrashReconciliationResult.NONE
  },
)
