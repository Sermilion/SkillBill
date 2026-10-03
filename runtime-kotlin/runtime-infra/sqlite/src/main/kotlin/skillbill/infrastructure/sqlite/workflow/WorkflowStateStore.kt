package skillbill.infrastructure.sqlite.workflow

import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureTaskExecutionLookupStore
import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureTaskRuntimeWorkerStore
import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureTaskWorkflowRowStore
import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureVerifyWorkflowStateStore
import skillbill.infrastructure.sqlite.workflow.goalrunner.child.GoalChildWorkflowStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.FeatureTaskExecutionLookupRepository
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import skillbill.ports.workflow.FeatureTaskWorkflowStateRepository
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock

internal const val DELETE_GOAL_CHILD_FIRST_STATUS_INDEX: Int = 2
internal const val MINIMUM_OWNER_TOKEN_LENGTH: Int = 16

private const val WORKFLOW_ID_BATCH_SIZE: Int = 900

internal class WorkflowStateStore private constructor(
  private val connection: Connection,
  private val featureTaskStore: FeatureTaskWorkflowStateStore,
  private val verifyStore: FeatureVerifyWorkflowStateStore,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
  private val transactionActive: Boolean,
) : WorkflowStateRepository,
  FeatureTaskWorkflowStateRepository by featureTaskStore,
  GoalChildWorkflowStateRepository by featureTaskStore,
  FeatureTaskRuntimeWorkerRepository by featureTaskStore {
  constructor(
    connection: Connection,
    clock: Clock,
    workflowSnapshotValidator: WorkflowSnapshotValidator,
    diagnostics: RuntimeDiagnostics,
    transactionActive: Boolean = false,
  ) : this(
    connection,
    FeatureTaskWorkflowStateStore(connection, clock, workflowSnapshotValidator, diagnostics, transactionActive),
    FeatureVerifyWorkflowStateStore(connection, clock, workflowSnapshotValidator),
    workflowSnapshotValidator,
    transactionActive,
  )

  override fun migrateFeatureTaskArtifacts(
    source: WorkflowStateRecord,
    targetArtifactsJson: String,
  ) {
    try {
      if (!transactionActive) {
        throw SkillBillRuntimeException(
          FeatureTaskRuntimeMigrationFailureCode.WRITE_FAILURE,
          "Durable output migration requires its owning immediate transaction.",
        )
      }
      if (featureTaskStore.getFeatureTaskWorkflow(source.workflowId) != source) {
        staleArtifactMigration()
      }
      workflowSnapshotValidator.validate(
        source.copy(artifactsJson = targetArtifactsJson).toSnapshot(),
        source.workflowName,
      )
      connection.prepareStatement(
        "UPDATE feature_task_workflows SET artifacts_json = ? WHERE workflow_id = ? AND artifacts_json = ?",
      ).use {
        it.bindAll(targetArtifactsJson, source.workflowId, source.artifactsJson)
        if (it.executeUpdate() != 1) {
          staleArtifactMigration()
        }
      }
    } catch (error: SQLException) {
      throw SkillBillRuntimeException(
        FeatureTaskRuntimeMigrationFailureCode.WRITE_FAILURE,
        "Durable output publication failed. The owning transaction must roll back before retry.",
        error,
      )
    }
  }

  private fun staleArtifactMigration(): Nothing =
    throw SkillBillRuntimeException(
      FeatureTaskRuntimeMigrationFailureCode.STALE_SOURCE,
      "Durable output migration source changed. Retry without resetting saved state.",
    )

  override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) {
    val source =
      when (family) {
        WorkflowFamily.VERIFY -> verifyStore.getWorkflow(snapshot.workflowId)
        WorkflowFamily.TASK_RUNTIME ->
          featureTaskStore.getFeatureTaskWorkflowAsMode(snapshot.workflowId, FeatureTaskWorkflowMode.RUNTIME)
      }
    saveRecord(family, snapshot.toRecord(source))
  }

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    when (family) {
      WorkflowFamily.VERIFY -> verifyStore.saveWorkflow(record)
      WorkflowFamily.TASK_RUNTIME ->
        featureTaskStore.saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.RUNTIME)
    }
  }

  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? =
    when (family) {
      WorkflowFamily.VERIFY -> verifyStore.getWorkflow(workflowId)
      WorkflowFamily.TASK_RUNTIME ->
        featureTaskStore.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
    }?.toSnapshot()

  override fun getAll(
    family: WorkflowFamily,
    workflowIds: Set<String>,
  ): Map<String, WorkflowStateSnapshot> =
    buildMap {
      workflowIds.chunked(WORKFLOW_ID_BATCH_SIZE).forEach { batch ->
        val records =
          when (family) {
            WorkflowFamily.VERIFY -> verifyStore.getWorkflows(batch.toSet())
            WorkflowFamily.TASK_RUNTIME ->
              connection.getFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.RUNTIME, batch.toSet())
          }
        records.forEach { (workflowId, record) -> put(workflowId, record.toSnapshot()) }
      }
    }

  override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> =
    when (family) {
      WorkflowFamily.VERIFY -> verifyStore.listWorkflows(limit)
      WorkflowFamily.TASK_RUNTIME -> featureTaskStore.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, limit)
    }.map(WorkflowStateRecord::toSnapshot)

  override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = list(family, 1).firstOrNull()

  override fun sessionSummary(
    family: WorkflowFamily,
    sessionId: String,
  ): WorkflowContinueSessionSummary {
    if (sessionId.isBlank()) {
      return WorkflowContinueSessionSummary.EMPTY
    }
    return when (family) {
      WorkflowFamily.VERIFY -> verifyStore.getSessionSummary(sessionId) ?: WorkflowContinueSessionSummary.EMPTY
      WorkflowFamily.TASK_RUNTIME -> WorkflowContinueSessionSummary.EMPTY
    }
  }
}

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

  override fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord) =
    rows.terminalizeLegacyProseFeatureTaskWorkflow(row)

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) = rows.saveFeatureTaskWorkflow(row, mode)

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

  override fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    rows.latestFeatureTaskWorkflow(mode)
}
