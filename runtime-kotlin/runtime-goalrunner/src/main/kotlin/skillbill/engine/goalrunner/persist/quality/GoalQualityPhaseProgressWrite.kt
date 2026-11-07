package skillbill.engine.goalrunner.persist.quality

import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress

public data class GoalQualityPhaseProgressWrite(
  val parentWorkflowId: String,
  val invocationId: String,
  val definitionId: String,
  val acceptedPlan: String,
  val progress: GoalQualityPhaseProgress,
  val finalValidationContentFingerprint: String? = null,
  val preQaBuildContentFingerprint: String? = null,
  val preQaBuildSourceProof: GoalQualityBuildSourceProof? = null,
)
