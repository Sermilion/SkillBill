package skillbill.ports.workflow

import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.mapToRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode

/**
 * Durable workflow-state persistence. Snapshot reads and writes are keyed by
 * [WorkflowFamily]; the adapter owns storage-table selection and any batching
 * its backing store requires. This remains the single port adapters implement.
 */
interface WorkflowStateRepository :
  FeatureTaskWorkflowStateRepository,
  GoalChildWorkflowStateRepository,
  FeatureTaskRuntimeWorkerRepository {
  fun migrateFeatureTaskArtifacts(
    source: WorkflowStateRecord,
    targetArtifactsJson: String,
  )

  fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  )

  fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  )

  fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot?

  fun getAll(
    family: WorkflowFamily,
    workflowIds: Set<String>,
  ): Map<String, WorkflowStateSnapshot>

  fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot>

  fun latest(family: WorkflowFamily): WorkflowStateSnapshot?

  fun sessionSummary(
    family: WorkflowFamily,
    sessionId: String,
  ): WorkflowContinueSessionSummary
}

fun WorkflowStateSnapshot.toRecord(source: WorkflowStateRecord? = null): WorkflowStateRecord = mapToRecord(source)

interface FeatureTaskExecutionLookupRepository {
  fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity)

  fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity?

  fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate>

  fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate>

  fun countGoalChildIdentities(normalizedIssueKey: String): Int

  fun claimFeatureTaskContinuation(
    workflowId: String,
    expectedUpdatedAt: String?,
  ): Boolean
}

interface FeatureTaskWorkflowStateRepository : FeatureTaskExecutionLookupRepository {
  fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord)

  fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  )

  fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord?

  fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int = 20,
  ): List<WorkflowStateRecord>

  fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord?
}

interface GoalChildWorkflowStateRepository {
  fun listGoalChildWorkflowIdsByParent(parentWorkflowId: String): List<String>

  fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int

  fun deleteGoalChildWorkflow(
    parentWorkflowId: String,
    subtaskId: Int,
    workflowId: String,
    scope: GoalChildWorkflowDeletionScope = GoalChildWorkflowDeletionScope.TERMINAL_ONLY,
  ): Int
}
