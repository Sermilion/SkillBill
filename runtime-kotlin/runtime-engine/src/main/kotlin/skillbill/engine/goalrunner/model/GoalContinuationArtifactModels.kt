package skillbill.engine.goalrunner.model

import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot

data class GoalSubtaskIdentity(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
)

data class HistoryArtifactAppend(
  val workflowId: String,
  val latestFamily: DurableWorkflowArtifactFamily?,
  val historyFamily: DurableWorkflowArtifactFamily,
  val retentionLimit: Int,
  val entryMap: GoalRunnerWirePayload,
)

data class GoalContinuationCandidate(
  val family: WorkflowFamily,
  val snapshot: WorkflowStateSnapshot,
  val goalContinuation: GoalContinuation,
  val outcome: GoalRunnerStoredOutcome?,
)

data class GoalRunnerBlockWrite(
  val family: WorkflowFamily,
  val record: WorkflowStateSnapshot,
  val blockedReason: String,
  val lastResumableStep: String,
  val supervisionEvent: GoalRunnerSupervisionEvent?,
  val causingChildWorkflowId: String? = null,
  val executionGeneration: Long? = null,
  val failureExitCode: Int? = null,
  val failureThrowableType: String? = null,
  val causingIncidentToken: String? = null,
  val causingAcceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
  val historicalBlockReference: GoalRunnerAcceptedBlockReference? = null,
)

internal data class GoalRunnerBlockWriteResult(
  val stepId: String,
  val observation: BlockIncidentObservation,
  val acceptedBlockReference: GoalRunnerAcceptedBlockReference,
)

data class GoalRunnerAcceptedBlock(
  val stepId: String,
  val incidentToken: String?,
  val acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
)
