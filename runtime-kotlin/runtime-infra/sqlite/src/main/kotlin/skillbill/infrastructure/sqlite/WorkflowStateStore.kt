package skillbill.infrastructure.sqlite

import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeMigrationFailureCode
import skillbill.error.shellcontent.terminalWorkflowWriteRefusedError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.workflow.artifact.FeatureTaskWorkflowArtifactsCodec
import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureTaskWorkflowStateStore
import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureVerifyWorkflowStateStore
import skillbill.infrastructure.sqlite.workflow.getFeatureTaskWorkflowRows
import skillbill.infrastructure.sqlite.workflow.goalContinuationColumns
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.FeatureTaskRuntimeWorkerRepository
import skillbill.ports.workflow.FeatureTaskWorkflowStateRepository
import skillbill.ports.workflow.GoalChildWorkflowStateRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.WorkflowStateWriteResult
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.isTerminal
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock

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
    targetArtifacts: DurableWorkflowArtifacts,
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
      val targetSnapshot = source.toSnapshot().copy(artifacts = targetArtifacts)
      workflowSnapshotValidator.validate(targetSnapshot, source.workflowName)
      val target = targetSnapshot.toRecord(source)
      val goalColumns = targetSnapshot.artifacts.goalContinuationColumns()
      connection.prepareStatement(
        """
        UPDATE feature_task_workflows
        SET artifacts_json = ?, parent_workflow_id = ?, goal_subtask_id = ?, goal_issue_key = ?
        WHERE workflow_id = ?
        """.trimIndent(),
      ).use {
        it.bindAll(
          FeatureTaskWorkflowArtifactsCodec.merge(target.artifactsJson, target.typedArtifacts),
          goalColumns.parentWorkflowId,
          goalColumns.subtaskId,
          goalColumns.issueKey,
          source.workflowId,
        )
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
    if (saveOrdinary(family, snapshot) == WorkflowStateWriteResult.TerminalRefused) {
      throw terminalWorkflowWriteRefusedError(snapshot.workflowId)
    }
  }

  override fun saveAcceptedSnapshotMutation(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) {
    val source = currentRecord(family, snapshot.workflowId)
    persistRecord(family, snapshot.toRecord(source), acceptedSnapshotMutation = true)
  }

  override fun saveOrdinary(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ): WorkflowStateWriteResult {
    val source = currentRecord(family, snapshot.workflowId)
    val record = snapshot.toRecord(source)
    val terminalResult = terminalOrdinaryWriteResult(source, record)
    if (terminalResult != null) return terminalResult
    persistRecord(family, record)
    return WorkflowStateWriteResult.Accepted
  }

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    val current = currentRecord(family, record.workflowId)
    when (terminalOrdinaryWriteResult(current, record)) {
      WorkflowStateWriteResult.TerminalRefused -> throw terminalWorkflowWriteRefusedError(record.workflowId)
      WorkflowStateWriteResult.Accepted -> return
      null -> persistRecord(family, record)
    }
  }

  private fun terminalOrdinaryWriteResult(
    current: WorkflowStateRecord?,
    proposed: WorkflowStateRecord,
  ): WorkflowStateWriteResult? {
    if (current == null || WorkflowStatus.fromWire(current.workflowStatus)?.isTerminal != true) return null
    return if (current.sameTerminalSnapshot(proposed)) {
      WorkflowStateWriteResult.Accepted
    } else {
      WorkflowStateWriteResult.TerminalRefused
    }
  }

  private fun persistRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
    acceptedSnapshotMutation: Boolean = false,
  ) {
    when (family) {
      WorkflowFamily.VERIFY -> verifyStore.saveWorkflow(record, acceptedSnapshotMutation)
      WorkflowFamily.TASK_RUNTIME ->
        featureTaskStore.saveFeatureTaskWorkflow(
          record,
          FeatureTaskWorkflowMode.RUNTIME,
          acceptedSnapshotMutation,
        )
    }
  }

  private fun currentRecord(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateRecord? =
    when (family) {
      WorkflowFamily.VERIFY -> verifyStore.getWorkflow(workflowId)
      WorkflowFamily.TASK_RUNTIME ->
        featureTaskStore.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
    }

  private fun WorkflowStateRecord.sameTerminalSnapshot(other: WorkflowStateRecord): Boolean =
    workflowStatus == other.workflowStatus && currentStepId == other.currentStepId &&
      stepsJson == other.stepsJson && artifactsJson == other.artifactsJson &&
      typedArtifacts == other.typedArtifacts && finishedAt == other.finishedAt

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
