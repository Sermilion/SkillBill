package skillbill.engine.featuretask.model.phase

import skillbill.contracts.SharedPayloadKeys

public data class FeatureTaskRuntimePhaseStepWireUpdate(
  public val stepId: String,
  public val status: String,
  public val attemptCount: Int,
) {
  public fun toWireMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.STEP_ID to stepId,
      SharedPayloadKeys.STATUS to status,
      "attempt_count" to attemptCount,
    )
}
