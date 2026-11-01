package skillbill.engine.featuretask.model.core

public data class FeatureTaskRuntimeCrashReconciliationResult(
  val reconciledCount: Int = 0,
  val reasonClassCounts: Map<String, Int> = emptyMap(),
) {
  public companion object {
    public val NONE: FeatureTaskRuntimeCrashReconciliationResult = FeatureTaskRuntimeCrashReconciliationResult()
  }
}

internal enum class FeatureTaskRuntimeCrashReconciliationReason(val wireValue: String) {
  LEASE_EXPIRED("lease_expired"),
}
