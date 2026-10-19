package skillbill.ports.workflow

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.goalrunner.model.GoalAttemptLedgerStatusEntry
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.workflow.model.FeatureTaskRuntimePhaseLedgerPage
import skillbill.ports.workflow.model.FeatureTaskRuntimeWorkflowHeader
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.WorkflowStateWriteResult
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.artifact.phaseLedger
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.goalContinuation

abstract class WorkflowStateRepositoryDefaults : WorkflowStateRepository {
  open override fun readGoalStatusSnapshot(workflowId: String): WorkflowStateSnapshot? =
    get(WorkflowFamily.TASK_RUNTIME, workflowId)

  open override fun readGoalStatusChildPage(
    normalizedIssueKey: String,
    offset: Int,
    limit: Int,
    subtaskIds: Set<Int>?,
  ): List<WorkflowStateSnapshot> =
    findFeatureTaskWorkflowsForIssue(FeatureTaskWorkflowMode.RUNTIME, normalizedIssueKey)
      .map { it.toSnapshot() }
      .filter { snapshot ->
        val continuation = DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuation()
        continuation != null && (subtaskIds == null || continuation.subtaskId in subtaskIds)
      }.drop(offset).take(limit)

  open override fun readGoalAttemptLedgerPage(
    workflowId: String,
    offset: Int,
    limit: Int,
  ): List<GoalAttemptLedgerStatusEntry> =
    (
      readGoalStatusSnapshot(
        workflowId,
      )?.artifacts?.let(DurableWorkflowArtifactFamily.GOAL_ATTEMPT_LEDGER::value) as? List<*>
    )
      .orEmpty().drop(offset).take(limit).map {
        GoalAttemptLedgerStatusEntry.fromWire(FeatureTaskRuntimeWorkflowArtifactMap.from(it))
      }

  open override fun readFeatureTaskArtifacts(
    workflowId: String,
    families: Set<DurableWorkflowArtifactFamily>,
  ): DurableWorkflowArtifacts? =
    get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts
      ?.let {
          artifacts ->
        DurableWorkflowArtifacts.fromMap(
          artifacts.toMap().filterKeys {
              key ->
            families.any { it.label() == key }
          },
        )
      }

  open override fun readFeatureTaskWorkflowHeader(workflowId: String): FeatureTaskRuntimeWorkflowHeader? =
    getFeatureTaskWorkflow(workflowId)?.let {
        row ->
      row.mode?.let { FeatureTaskRuntimeWorkflowHeader(it, row.currentStepId) }
    }

  open override fun readFeatureTaskPhaseLedgerPage(
    workflowId: String,
    offset: Int,
    limit: Int,
  ): FeatureTaskRuntimePhaseLedgerPage? {
    val ledger = get(WorkflowFamily.TASK_RUNTIME, workflowId)?.artifacts?.phaseLedger() ?: return null
    return FeatureTaskRuntimePhaseLedgerPage(
      ledger.drop(offset).take(limit),
      (offset + limit).takeIf { it < ledger.size },
    )
  }

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

  open override fun findFeatureTaskWorkflowsForIssue(
    mode: FeatureTaskWorkflowMode,
    normalizedIssueKey: String,
    repositoryIdentity: String?,
  ): List<WorkflowStateRecord> =
    listFeatureTaskWorkflows(mode, Int.MAX_VALUE).filter { row ->
      val key =
        row.issueKey ?: run {
          val artifacts = JsonCodec.parseValue(row.artifactsJson) as Map<*, *>
          val runtime = artifacts[DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()] as? Map<*, *>
          val continuation =
            artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()] as? Map<*, *>
          (runtime?.get(SharedPayloadKeys.ISSUE_KEY) ?: continuation?.get(SharedPayloadKeys.ISSUE_KEY))?.toString()
        }
      key?.trim()?.uppercase() == normalizedIssueKey
    }

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

  open override fun bindFeatureTaskRuntimeWorkerPhaseCoordinates(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    phaseId: String,
    phaseAttempt: Int,
  ): Boolean = false

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

  open override fun saveAcceptedSnapshotMutation(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) {
    save(family, snapshot)
  }

  open override fun saveOrdinary(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ): WorkflowStateWriteResult {
    save(family, snapshot)
    return WorkflowStateWriteResult.Accepted
  }

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
