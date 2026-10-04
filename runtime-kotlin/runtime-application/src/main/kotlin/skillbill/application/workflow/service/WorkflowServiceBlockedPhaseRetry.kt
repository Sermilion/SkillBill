package skillbill.application.workflow.service

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.clearDecompositionManifestProjectionFailure
import skillbill.application.decomposition.model.DecompositionManifestProjectionFailurePersistence
import skillbill.application.decomposition.persistDecompositionManifestProjectionFailure
import skillbill.application.workflow.decomposition.PendingDecompositionProjection
import skillbill.application.workflow.decomposition.goalContinuationParentWorkflowIdForSettlement
import skillbill.application.workflow.decomposition.updateGoalParentForBlockedPhaseRetry
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.persist.FeatureTaskRuntimePhaseLedgerDecoder
import skillbill.application.workflow.persist.WorkflowPersistenceContext
import skillbill.application.workflow.persist.buildUpdateOk
import skillbill.application.workflow.persist.decodeFeatureTaskRuntimePhaseRecords
import skillbill.contracts.SharedPayloadKeys
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.repair.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH
import java.time.Clock
import java.time.ZoneOffset

internal class WorkflowServiceBlockedPhaseRetry(
  private val engine: WorkflowEngine,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val decompositionManifestStore: DecompositionManifestStore,
  private val decompositionManifestWriter: DecompositionManifestWriter,
  private val repositoryRoot: RepositoryRoot,
  private val runtimeDiagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val repositoryCheckpointIdentity: () -> String = { "" },
) {
  fun retry(
    database: DatabaseSessionFactory,
    workflowId: String,
    phaseId: String,
    reason: String,
  ): WorkflowUpdateResult {
    val normalizedReason = reason.trim()
    if (
      normalizedReason.isEmpty() ||
      normalizedReason.length > FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH
    ) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Blocked-phase retry reason must contain " +
          "1..$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_REASON_MAX_LENGTH characters.",
      )
    }
    val normalizedPhaseId = phaseId.trim()
    val family = WorkflowFamily.TASK_RUNTIME
    if (normalizedPhaseId !in family.definition.stepIds) {
      return WorkflowUpdateResult.Error(
        workflowId,
        "Unknown runtime phase '$normalizedPhaseId'. Allowed: ${family.definition.stepIds.joinToString()}.",
      )
    }
    val request = BlockedPhaseRetryRequest(workflowId, normalizedPhaseId, normalizedReason)
    val persistence =
      database.transaction { unitOfWork ->
        retryInTransaction(unitOfWork, request)
      }
    persistence.pendingProjection?.let { pending -> reconcilePendingProjection(database, pending) }
    return persistence.result
  }

  private fun reconcilePendingProjection(
    database: DatabaseSessionFactory,
    pending: PendingDecompositionProjection,
  ) {
    val outcome =
      decompositionManifestWriter.writeProjectionFromWorkflowState(
        repositoryRoot.path,
        pending.artifacts,
        decompositionManifestValidator,
        decompositionManifestStore,
      )
    when (outcome) {
      is DecompositionManifestProjectionOutcome.Failed ->
        database.transaction { unitOfWork ->
          val persistence =
            persistDecompositionManifestProjectionFailure(
              engine,
              unitOfWork,
              pending.ownerWorkflowId,
              outcome,
            )
          warnIfProjectionOwnerAbsent(persistence, pending.ownerWorkflowId)
        }
      is DecompositionManifestProjectionOutcome.Written ->
        database.transaction { unitOfWork ->
          val persistence =
            clearDecompositionManifestProjectionFailure(
              engine,
              unitOfWork,
              pending.ownerWorkflowId,
            )
          warnIfProjectionOwnerAbsent(persistence, pending.ownerWorkflowId)
        }
      DecompositionManifestProjectionOutcome.Absent -> Unit
    }
  }

  private fun warnIfProjectionOwnerAbsent(
    persistence: DecompositionManifestProjectionFailurePersistence,
    ownerWorkflowId: String,
  ) {
    if (persistence == DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT) {
      runtimeDiagnostics.warning(
        "seam=decomposition_projection_settlement value_expected=workflow_row " +
          "value_used=absent owner_workflow_id=$ownerWorkflowId",
      )
    }
  }

  private fun retryInTransaction(
    unitOfWork: UnitOfWork,
    request: BlockedPhaseRetryRequest,
  ): BlockedPhaseRetryPersistence {
    val family = WorkflowFamily.TASK_RUNTIME
    val existing =
      unitOfWork.workflowStates.get(family, request.workflowId)
        ?: return BlockedPhaseRetryPersistence.error(
          WorkflowUpdateResult.Error(
            request.workflowId,
            "Unknown runtime workflow_id '${request.workflowId}'.",
            unitOfWork.dbPath.toString(),
          ),
        )
    if (family.definition.isTerminalStatus(existing.workflowStatus)) {
      return BlockedPhaseRetryPersistence.error(
        WorkflowUpdateResult.Error(
          request.workflowId,
          "Runtime workflow '${request.workflowId}' is already terminal with status '${existing.workflowStatus}'.",
          unitOfWork.dbPath.toString(),
        ),
      )
    }
    val artifacts = existing.artifacts
    val phaseRecords = decodeFeatureTaskRuntimePhaseRecords(artifacts)
    val ledger = FeatureTaskRuntimePhaseLedgerDecoder.decode(artifacts)
    val blockedRecord =
      phaseRecords[request.phaseId]
        ?: return BlockedPhaseRetryPersistence.error(
          WorkflowUpdateResult.Error(
            request.workflowId,
            "Runtime workflow '${request.workflowId}' has no durable phase record for '${request.phaseId}'.",
            unitOfWork.dbPath.toString(),
          ),
        )
    return if (blockedRecord.status.workflowStepStatus() != WorkflowStepStatus.BLOCKED) {
      BlockedPhaseRetryPersistence.error(
        WorkflowUpdateResult.Error(
          request.workflowId,
          "Runtime workflow '${request.workflowId}' phase '${request.phaseId}' is " +
            "'${blockedRecord.status}', not blocked.",
          unitOfWork.dbPath.toString(),
        ),
      )
    } else {
      persistBlockedPhaseRetry(
        unitOfWork,
        existing,
        request,
        BlockedPhaseRetryState(phaseRecords, ledger, blockedRecord),
      )
    }
  }

  private fun persistBlockedPhaseRetry(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    request: BlockedPhaseRetryRequest,
    state: BlockedPhaseRetryState,
  ): BlockedPhaseRetryPersistence {
    val input = blockedPhaseRetryInput(request, state, clock)
    val family = WorkflowFamily.TASK_RUNTIME
    val updated = engine.updateRecord(family.definition, existing, input)
    unitOfWork.workflowStates.save(family, updated)
    val projectionArtifacts =
      engine.updateGoalParentForBlockedPhaseRetry(
        unitOfWork = unitOfWork,
        childWorkflowId = request.workflowId,
        childArtifacts = updated.artifacts,
        phaseId = request.phaseId,
        validator = decompositionManifestValidator,
      )
    val pendingProjection =
      projectionArtifacts?.let { artifacts ->
        val ownerWorkflowId =
          goalContinuationParentWorkflowIdForSettlement(updated.artifacts)
            ?: request.workflowId
        PendingDecompositionProjection(ownerWorkflowId, artifacts)
      }
    return BlockedPhaseRetryPersistence(
      result =
        buildUpdateOk(
          engine,
          family.definition,
          updated,
          input,
          WorkflowPersistenceContext(
            dbPath = unitOfWork.dbPath.toString(),
            repositoryCheckpointIdentity = repositoryCheckpointIdentity,
          ),
        ),
      pendingProjection = pendingProjection,
    )
  }
}

private fun blockedPhaseRetryInput(
  request: BlockedPhaseRetryRequest,
  state: BlockedPhaseRetryState,
  clock: Clock,
): WorkflowUpdateInput {
  val now = clock.instant().atOffset(ZoneOffset.UTC).toString()
  val retryEntry =
    FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
      sequenceNumber = (state.ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
      timestamp = now,
      phaseId = request.phaseId,
      attemptCount = state.blockedRecord.attemptCount,
      resolvedAgentId = state.blockedRecord.resolvedAgentId,
    )
  return WorkflowUpdateInput(
    workflowStatus = WorkflowStatus.RUNNING,
    currentStepId = request.phaseId,
    stepUpdates =
      WorkflowStepUpdates.from(
        listOf(
          mapOf(
            SharedPayloadKeys.STEP_ID to request.phaseId,
            SharedPayloadKeys.STATUS to WorkflowStepStatus.PENDING.wireValue,
            "attempt_count" to 0,
          ),
        ),
      ),
    artifactsPatch =
      WorkflowArtifactPatch.from(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            state.reopenedPhaseRecords().mapValues { (_, record) -> record.asWorkflowArtifactEntry() },
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(
            (state.ledger.map { it.asWorkflowArtifactEntry() } + retryEntry.asWorkflowArtifactEntry()).takeLast(
              FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
            ),
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.entry(
            mapOf(
              SharedPayloadKeys.PHASE_ID to request.phaseId,
              "reason" to request.reason,
              "retried_at" to now,
              "previous_blocked_reason" to state.blockedRecord.blockedReason,
              "previous_blocked_record" to state.blockedRecord.asWorkflowArtifactEntry(),
            ),
          ),
        ),
      ),
    sessionId = "",
  )
}

private data class BlockedPhaseRetryRequest(
  val workflowId: String,
  val phaseId: String,
  val reason: String,
)

private data class BlockedPhaseRetryState(
  val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val blockedRecord: FeatureTaskRuntimePhaseRecord,
) {
  fun reopenedPhaseRecords(): Map<String, FeatureTaskRuntimePhaseRecord> =
    LinkedHashMap(phaseRecords).apply {
      this[blockedRecord.phaseId] =
        blockedRecord.copy(
          status = WorkflowStepStatus.PENDING,
          finishedAt = null,
          durationMillis = null,
          outputArtifact = null,
          rejectedOutput = null,
          blockedReason = null,
          failureDisposition = null,
          fileManifestBefore = emptyList(),
          fileManifestAfter = emptyList(),
          fileManifestIntroduced = emptyList(),
        )
    }
}

private data class BlockedPhaseRetryPersistence(
  val result: WorkflowUpdateResult,
  val pendingProjection: PendingDecompositionProjection?,
) {
  companion object {
    fun error(result: WorkflowUpdateResult.Error): BlockedPhaseRetryPersistence =
      BlockedPhaseRetryPersistence(result, pendingProjection = null)
  }
}
