package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.goalrunner.model.GoalAttemptLedgerStatusEntry
import skillbill.infrastructure.sqlite.workflow.goalrunner.child.GoalChildWorkflowStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.FeatureTaskExecutionLookupRepository
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import skillbill.ports.workflow.FeatureTaskWorkflowStateRepository
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.FeatureTaskRuntimePhaseLedgerPage
import skillbill.ports.workflow.model.FeatureTaskRuntimeWorkflowHeader
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.sql.Connection
import java.time.Clock

internal class FeatureTaskWorkflowStateStore(
  connection: Connection,
  clock: Clock,
  workflowSnapshotValidator: WorkflowSnapshotValidator,
  diagnostics: RuntimeDiagnostics,
  transactionActive: Boolean = false,
) : FeatureTaskWorkflowStateRepository,
  FeatureTaskExecutionLookupRepository by FeatureTaskExecutionLookupStore(connection),
  GoalChildWorkflowStateRepository by GoalChildWorkflowStore(connection),
  FeatureTaskRuntimeWorkerRepository by FeatureTaskRuntimeWorkerStore(connection, diagnostics, transactionActive) {
  private val rows = FeatureTaskWorkflowRowStore(connection, clock, workflowSnapshotValidator)
  private val phaseReads = FeatureTaskPhaseReadStore(connection)

  private val goalStatusReads = GoalStatusReadStore(connection)

  override fun readGoalStatusSnapshot(workflowId: String): WorkflowStateSnapshot? = goalStatusReads.snapshot(workflowId)

  override fun readGoalStatusChildPage(
    normalizedIssueKey: String,
    offset: Int,
    limit: Int,
    subtaskIds: Set<Int>?,
    parentWorkflowId: String?,
  ): List<WorkflowStateSnapshot> =
    goalStatusReads.childPage(
      normalizedIssueKey,
      offset,
      limit,
      subtaskIds,
      parentWorkflowId,
    )

  override fun readGoalAttemptLedgerPage(
    workflowId: String,
    offset: Int,
    limit: Int,
  ): List<GoalAttemptLedgerStatusEntry> = goalStatusReads.ledgerPage(workflowId, offset, limit)

  override fun readFeatureTaskArtifacts(
    workflowId: String,
    families: Set<DurableWorkflowArtifactFamily>,
  ): DurableWorkflowArtifacts? = phaseReads.artifacts(workflowId, families)

  override fun readFeatureTaskWorkflowHeader(workflowId: String): FeatureTaskRuntimeWorkflowHeader? =
    phaseReads.header(
      workflowId,
    )

  override fun readFeatureTaskPhaseLedgerPage(
    workflowId: String,
    offset: Int,
    limit: Int,
  ): FeatureTaskRuntimePhaseLedgerPage? = phaseReads.ledgerPage(workflowId, offset, limit)

  override fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord) =
    rows.terminalizeLegacyProseFeatureTaskWorkflow(row)

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) = rows.saveFeatureTaskWorkflow(row, mode)

  fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
    acceptedSnapshotMutation: Boolean,
  ) = rows.saveFeatureTaskWorkflow(row, mode, acceptedSnapshotMutation)

  override fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    rows.getFeatureTaskWorkflow(workflowId)

  override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? = rows.getFeatureTaskWorkflowAsMode(workflowId, mode)

  override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> = rows.listFeatureTaskWorkflows(mode, limit)

  override fun findFeatureTaskWorkflowsForIssue(
    mode: FeatureTaskWorkflowMode,
    normalizedIssueKey: String,
    repositoryIdentity: String?,
  ): List<WorkflowStateRecord> = rows.findFeatureTaskWorkflowsForIssue(mode, normalizedIssueKey, repositoryIdentity)

  override fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    rows.latestFeatureTaskWorkflow(mode)
}
