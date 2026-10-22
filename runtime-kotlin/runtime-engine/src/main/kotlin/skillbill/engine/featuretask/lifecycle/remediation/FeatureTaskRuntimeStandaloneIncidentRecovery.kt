package skillbill.engine.featuretask.lifecycle.remediation

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.featuretask.phase.record.asPendingForOperatorResume
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry as phaseArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry as operatorRetryArtifactEntry
import skillbill.workflow.taskruntime.artifact.phaseLedgerFromWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.retainPhaseLedgerEvidence
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import java.time.Clock
import java.time.ZoneOffset

@Inject
internal class FeatureTaskRuntimeStandaloneIncidentRecovery(
  private val engine: WorkflowEngine,
  private val clock: Clock,
) {
  fun reopen(
    unit: UnitOfWork,
    workflowId: String,
    invocationId: String,
    phaseId: String,
    reason: String,
    admittedAttempt: BlockIncidentRecoveryAttempt,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean {
    if (reason.isBlank() || reason.length > MAX_RECOVERY_REASON_CHARS) return false
    val family = WorkflowFamily.TASK_RUNTIME
    val workflow = unit.workflowStates.get(family, workflowId) ?: return false
    val persistedAttempt = unit.blockIncidentRecoveries.byIdempotencyKey(admittedAttempt.idempotencyKey)
    if (persistedAttempt != admittedAttempt || admittedAttempt.status != BlockIncidentRecoveryAttemptStatus.APPLYING ||
      admittedAttempt.ownerToken.isNullOrBlank() || admittedAttempt.ownerGeneration == null ||
      admittedAttempt.ownerExpiresAt?.isAfter(clock.instant()) != true ||
      admittedAttempt.targetWorkflowId != workflowId || admittedAttempt.targetInvocationId != invocationId ||
      admittedAttempt.occurrenceToken != BlockIncidentIdentity.fromArtifacts(workflow.artifacts)?.incidentToken
    ) {
      return false
    }
    val identity = unit.workflowStates.getFeatureTaskExecutionIdentity(workflowId) ?: return false
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity != expectedIdentity || identity.workflowId != workflowId ||
      identity.mode != FeatureTaskWorkflowMode.RUNTIME || identity.routeScope != FeatureTaskRouteScope.STANDALONE
    ) {
      return false
    }
    val storedPlan = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(workflow.artifacts)
      ?: return false
    if (storedPlan != JsonCodec.parseValue(expectedPlan.encoded().toString(Charsets.UTF_8))) return false
    if (workflow.workflowStatus != WorkflowStatus.BLOCKED || workflow.currentStepId != phaseId) return false
    if (unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) != null) return false
    val records = phaseRecordsFromWorkflowArtifacts(workflow.artifacts)
    val blocked = records[phaseId]?.takeIf { it.status == WorkflowStepStatus.BLOCKED } ?: return false
    val step = workflow.steps.firstOrNull { it.stepId == phaseId } ?: return false
    if (step.status != WorkflowStepStatus.BLOCKED || step.attemptCount != blocked.attemptCount) return false

    val ledger = phaseLedgerFromWorkflowArtifacts(workflow.artifacts)
    val reopened = LinkedHashMap(records).apply { put(phaseId, blocked.asPendingForOperatorResume()) }
    val now = clock.instant().atOffset(ZoneOffset.UTC).toString()
    val retry = FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
      sequenceNumber = Math.addExact(ledger.maxOfOrNull { it.sequenceNumber } ?: -1, 1),
      timestamp = now,
      phaseId = phaseId,
      attemptCount = blocked.attemptCount,
      resolvedAgentId = blocked.resolvedAgentId,
      recoveryAttemptId = admittedAttempt.attemptId,
      preserveBudget = true,
    )
    val updates = WorkflowUpdateInput(
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = phaseId,
      stepUpdates = WorkflowStepUpdates.from(
        listOf(
          mapOf(
            SharedPayloadKeys.STEP_ID to phaseId,
            SharedPayloadKeys.STATUS to WorkflowStepStatus.PENDING.wireValue,
            FeatureTaskRuntimePhasePayloadKeys.ATTEMPT_COUNT to step.attemptCount,
          ),
        ),
      ),
      artifactsPatch = WorkflowArtifactPatch.from(
        mapOf(
          DecompositionManifestPayloadKeys.BLOCKED_REASON to null,
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            reopened.mapValues { (_, record) -> record.phaseArtifactEntry() },
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(
            retainPhaseLedgerEvidence(ledger + retry, FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT)
              .map { it.phaseArtifactEntry() },
          ),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.entry(
            FeatureTaskRuntimeOperatorBlockRetry(
              phaseId = phaseId,
              reason = reason,
              retriedAt = now,
              recoveryAttemptId = admittedAttempt.attemptId,
              preserveBudget = true,
            ).operatorRetryArtifactEntry(
              previousBlockedReason = blocked.blockedReason.orEmpty(),
              reopenedPhaseIds = listOf(phaseId),
            ),
          ),
        ),
      ),
      sessionId = "",
    )
    unit.workflowStates.save(family, engine.updateRecord(family.definition, workflow, updates))
    return true
  }

  private companion object {
    const val MAX_RECOVERY_REASON_CHARS = 256
  }
}
