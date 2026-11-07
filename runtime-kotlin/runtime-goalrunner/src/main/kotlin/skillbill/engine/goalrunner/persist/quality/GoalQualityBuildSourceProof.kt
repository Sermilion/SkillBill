package skillbill.engine.goalrunner.persist.quality

import skillbill.goalrunner.model.quality.GoalQualityQaSourceWitness

public data class GoalQualityBuildSourceProof(
  public val parentWorkflowId: String,
  public val invocationId: String,
  public val acceptedPlan: String,
  public val progressRevision: Long,
  public val phaseId: String,
  public val attempt: Int,
  public val terminalSequenceNumber: Int,
  public val acceptedContentFingerprint: String,
  public val observedCurrentContentFingerprint: String,
  public val acceptedQaSourceWitness: GoalQualityQaSourceWitness? = null,
)
