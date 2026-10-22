package skillbill.workflow.taskruntime.model.blockincident

import skillbill.workflow.model.WorkflowStatus

data class BlockIncidentRecoveryFacts(
  val workflowStatus: WorkflowStatus?,
  val currentAttempt: Int?,
  val currentGeneration: Long?,
  val attemptLimitRemaining: Boolean,
  val operatorDecisionForbidsRetry: Boolean,
  val gateFailureCausedBlock: Boolean,
  val confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  val targetWorkflowId: String?,
  val targetInvocationId: String?,
  val phaseId: String?,
)
