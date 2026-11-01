package skillbill.engine.featuretask.model.core

public data class FeatureTaskRuntimeRegenerationTelemetry(
  internal val activationCount: Int = 0,
  public val attemptCount: Int = 0,
  public val outcomeCounts: Map<String, Int> = emptyMap(),
)

public data class FeatureTaskRuntimeFindingVerificationTelemetry(
  internal val verifiedCount: Int = 0,
  internal val rejectedCount: Int = 0,
  public val reviewFixCapExhausted: Boolean? = null,
)
