package skillbill.engine.goalrunner.persist.quality

import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus

public data class GoalQualityValidationSourceProof(
  public val sourceContractVersion: String,
  public val parentWorkflowId: String,
  public val invocationId: String,
  public val acceptedPlanFingerprint: String,
  public val validationOccurrence: Int,
  public val executionStatus: GoalQualityExecutionStatus,
  public val progressRevision: Long,
  public val phaseId: String,
  public val attempt: Int,
  public val ledgerSequenceNumber: Int?,
  public val contentFingerprint: String?,
  public val sourceIsCurrent: Boolean,
  public val acceptedOutputFingerprint: String?,
)
