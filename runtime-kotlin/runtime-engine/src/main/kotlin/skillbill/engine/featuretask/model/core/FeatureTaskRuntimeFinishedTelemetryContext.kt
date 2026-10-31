package skillbill.engine.featuretask.model.core

import skillbill.application.telemetry.model.FeatureTaskRuntimeAgentContext
import skillbill.contracts.telemetry.TelemetryMeasurementAvailability

public const val PHASE_STRATEGIES_DISPATCH_JOIN_SEAM: String = "telemetry.phase_strategies.dispatch_join"

public const val PHASE_STRATEGIES_DISPATCH_JOIN_EXPECTED: String = "admitted dispatch entry"

public data class FeatureTaskRuntimePhaseStrategies(
  val availability: TelemetryMeasurementAvailability,
  val values: Map<String, Map<String, Any>>?,
  val missingPhaseIds: List<String> = emptyList(),
)

public data class FeatureTaskRuntimeFinishedTelemetryContext(
  val telemetrySessionId: String,
  val phaseOutcomes: () -> Map<String, String>,
  public val phaseStrategies: () -> FeatureTaskRuntimePhaseStrategies = {
    FeatureTaskRuntimePhaseStrategies(
      availability = TelemetryMeasurementAvailability.UNAVAILABLE_NO_DURABLE_STATE,
      values = null,
    )
  },
  public val reviewFixIterationCount: () -> Int,
  public val auditGapIterationCount: () -> Int? = { null },
  public val agentContext: () -> FeatureTaskRuntimeAgentContext = { FeatureTaskRuntimeAgentContext() },
  internal val findingVerificationTelemetry: () -> FeatureTaskRuntimeFindingVerificationTelemetry = {
    FeatureTaskRuntimeFindingVerificationTelemetry()
  },
  internal val regenerationTelemetry: () -> FeatureTaskRuntimeRegenerationTelemetry = {
    FeatureTaskRuntimeRegenerationTelemetry()
  },
  internal val phaseTokenData: () -> Pair<String?, Int?> = { null to null },
  internal val crashReconciliation: () -> FeatureTaskRuntimeCrashReconciliationResult = {
    FeatureTaskRuntimeCrashReconciliationResult.NONE
  },
)
