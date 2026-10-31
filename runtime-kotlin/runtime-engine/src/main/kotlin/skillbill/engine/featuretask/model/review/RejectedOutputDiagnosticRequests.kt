package skillbill.engine.featuretask.model.review

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass

internal data class RejectedOutputDiagnosticDegradeRequest(
  public val workflowId: String,
  public val operation: String,
  public val conflictingKey: String,
  public val phaseId: String,
  public val attempt: Int,
  public val repairTurn: Int?,
  public val generation: Int,
)

internal data class RejectedOutputDiagnosticPersistRequest(
  public val workflowId: String,
  public val operation: String,
  public val conflictingKey: String,
  public val phaseId: String,
  public val attempt: Int,
  public val repairTurn: Int?,
  public val generation: Int,
  public val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
)
