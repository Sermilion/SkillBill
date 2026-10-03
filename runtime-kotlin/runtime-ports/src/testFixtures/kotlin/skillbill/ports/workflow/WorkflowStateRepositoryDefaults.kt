package skillbill.ports.workflow

import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode

abstract class WorkflowStateRepositoryDefaults : WorkflowStateRepository {
  open override fun migrateFeatureTaskArtifacts(
    source: WorkflowStateRecord,
    targetArtifactsJson: String,
  ) {
    throw UnsupportedOperationException("Fixture does not support durable artifact migration")
  }

  open override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  open override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? = null

  open override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  open override fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> = emptyList()

  open override fun countGoalChildIdentities(normalizedIssueKey: String): Int = 0

  open override fun claimFeatureTaskContinuation(
    workflowId: String,
    expectedUpdatedAt: String?,
  ): Boolean = false

  open override fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord) = Unit

  open override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) = Unit

  open override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? = null

  open override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? = null

  open override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> = emptyList()

  open override fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    listFeatureTaskWorkflows(mode, 1).firstOrNull()

  open override fun listGoalChildWorkflowIdsByParent(parentWorkflowId: String): List<String> = emptyList()

  open override fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int = 0

  open override fun deleteGoalChildWorkflow(
    parentWorkflowId: String,
    subtaskId: Int,
    workflowId: String,
    scope: GoalChildWorkflowDeletionScope,
  ): Int = 0

  open override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership? = null

  open override fun acquireFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean = false

  open override fun reserveFeatureTaskRuntimeWorkerTakeover(
    workflowId: String,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = false

  open override fun transferFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = false

  open override fun heartbeatFeatureTaskRuntimeWorker(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  open override fun releaseFeatureTaskRuntimeWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = false

  open override fun releaseFeatureTaskRuntimeWorkerIfExpired(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    nowInstant: String,
  ): Boolean = false

  open override fun findFeatureTaskRuntimeCrashReconciliationCandidates(
    nowInstant: String,
  ): List<FeatureTaskRuntimeCrashReconciliationCandidate> = emptyList()

  open override fun reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean = false

  open override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) = Unit

  open override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) = Unit

  open override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? = null

  open override fun getAll(
    family: WorkflowFamily,
    workflowIds: Set<String>,
  ): Map<String, WorkflowStateSnapshot> = emptyMap()

  open override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> = emptyList()

  open override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = list(family, 1).firstOrNull()

  open override fun sessionSummary(
    family: WorkflowFamily,
    sessionId: String,
  ): WorkflowContinueSessionSummary = WorkflowContinueSessionSummary.EMPTY
}
