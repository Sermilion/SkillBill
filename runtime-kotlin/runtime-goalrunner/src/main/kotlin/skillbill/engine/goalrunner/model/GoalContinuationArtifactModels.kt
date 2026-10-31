package skillbill.engine.goalrunner.model

import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.goalrunner.model.GoalContinuation
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal data class GoalSubtaskIdentity(
  val workflowId: String,
  val issueKey: String,
  val subtaskId: Int,
)

internal data class HistoryArtifactAppend(
  val workflowId: String,
  val latestFamily: DurableWorkflowArtifactFamily?,
  val historyFamily: DurableWorkflowArtifactFamily,
  val retentionLimit: Int,
  val entryMap: GoalRunnerWirePayload,
)

public data class GoalContinuationCandidate(
  public val family: WorkflowFamily,
  public val snapshot: WorkflowStateSnapshot,
  public val goalContinuation: GoalContinuation,
  public val outcome: GoalRunnerStoredOutcome?,
)

public data class GoalRunnerBlockRequest(
  public val workflowId: String,
  public val blockedReason: String,
  public val lastResumableStep: String,
  internal val supervisionEvent: GoalRunnerSupervisionEvent? = null,
  internal val causingChildWorkflowId: String? = null,
  public val executionGeneration: Long? = null,
  internal val executionOwnerToken: String? = null,
  internal val failureExitCode: Int? = null,
  internal val failureThrowableType: String? = null,
  internal val causingAcceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
  public val historicalBlockReference: GoalRunnerAcceptedBlockReference? = null,
  public val stopReason: GoalRunnerStopReason? = null,
  public val failureCode: String? = null,
  public val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
)

internal data class GoalRunnerBlockWrite(
  public val family: WorkflowFamily,
  public val record: WorkflowStateSnapshot,
  public val blockedReason: String,
  public val lastResumableStep: String,
  internal val supervisionEvent: GoalRunnerSupervisionEvent?,
  internal val causingChildWorkflowId: String? = null,
  public val executionGeneration: Long? = null,
  internal val failureExitCode: Int? = null,
  internal val failureThrowableType: String? = null,
  internal val causingAcceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
  public val historicalBlockReference: GoalRunnerAcceptedBlockReference? = null,
  public val stopReason: GoalRunnerStopReason? = null,
  public val failureCode: String? = null,
  public val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
)

internal data class GoalRunnerBlockWriteResult(
  public val stepId: String,
  public val observation: BlockIncidentObservation,
  public val acceptedBlockReference: GoalRunnerAcceptedBlockReference,
)

public data class GoalRunnerAcceptedBlock(
  public val stepId: String,
  public val incidentToken: String?,
  public val acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
)
