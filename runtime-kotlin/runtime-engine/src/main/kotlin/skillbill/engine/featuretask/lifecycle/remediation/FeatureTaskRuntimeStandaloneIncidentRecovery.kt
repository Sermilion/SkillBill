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
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.phaseLedgerFromWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.retainPhaseLedgerEvidence
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import java.time.Clock
import java.time.ZoneOffset
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry as operatorRetryArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry as phaseArtifactEntry

@Inject
class FeatureTaskRuntimeStandaloneIncidentRecovery(
  private val clock: Clock,
) {
  private val engine = WorkflowEngine()

  internal fun reopen(
    unit: UnitOfWork,
    request: StandaloneIncidentRecoveryRequest,
  ): Boolean {
    val workflow =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.expectedIdentity.workflowId)
        ?: return false
    if (!admissionMatches(unit, workflow, request) || !executionMatches(unit, workflow, request)) return false
    val blocked =
      phaseRecordsFromWorkflowArtifacts(workflow.artifacts)[request.phaseId]
        ?.takeIf { it.status == WorkflowStepStatus.BLOCKED } ?: return false
    val step = workflow.step(request.phaseId)
    return if (step?.status != WorkflowStepStatus.BLOCKED || step.attemptCount != blocked.attemptCount) {
      false
    } else {
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        engine.updateRecord(WorkflowFamily.TASK_RUNTIME.definition, workflow, reopenUpdate(workflow, blocked, request)),
      )
      true
    }
  }

  private fun admissionMatches(
    unit: UnitOfWork,
    workflow: WorkflowStateSnapshot,
    request: StandaloneIncidentRecoveryRequest,
  ): Boolean {
    val attempt = request.admittedAttempt
    val reasonValid = request.reason.isNotBlank() && request.reason.length <= MAX_RECOVERY_REASON_CHARS
    val owned =
      attempt.status == BlockIncidentRecoveryAttemptStatus.APPLYING && !attempt.ownerToken.isNullOrBlank() &&
        attempt.ownerGeneration != null && attempt.ownerExpiresAt?.isAfter(clock.instant()) == true
    val targetMatches =
      attempt.targetWorkflowId == workflow.workflowId &&
        attempt.targetInvocationId == request.invocationId &&
        attempt.occurrenceToken == BlockIncidentIdentity.fromArtifacts(workflow.artifacts)?.incidentToken
    return reasonValid && owned && targetMatches &&
      unit.blockIncidentRecoveries.byIdempotencyKey(attempt.idempotencyKey) == attempt
  }

  private fun executionMatches(
    unit: UnitOfWork,
    workflow: WorkflowStateSnapshot,
    request: StandaloneIncidentRecoveryRequest,
  ): Boolean {
    val identity = unit.workflowStates.getFeatureTaskExecutionIdentity(workflow.workflowId) ?: return false
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    val identityMatches =
      identity == request.expectedIdentity && identity.workflowId == workflow.workflowId &&
        identity.mode == FeatureTaskWorkflowMode.RUNTIME && identity.routeScope == FeatureTaskRouteScope.STANDALONE
    val storedPlan =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(workflow.artifacts)
        ?: return false
    val blocked = workflow.workflowStatus == WorkflowStatus.BLOCKED && workflow.currentStepId == request.phaseId
    return identityMatches && blocked && storedPlan ==
      JsonCodec.parseValue(
        request.expectedPlan.encoded().toString(Charsets.UTF_8),
      ) &&
      unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflow.workflowId) == null
  }

  private fun reopenUpdate(
    workflow: WorkflowStateSnapshot,
    blocked: FeatureTaskRuntimePhaseRecord,
    request: StandaloneIncidentRecoveryRequest,
  ): WorkflowUpdateInput {
    val phaseId = request.phaseId
    val reason = request.reason
    val admittedAttempt = request.admittedAttempt
    val records = phaseRecordsFromWorkflowArtifacts(workflow.artifacts)
    val ledger = phaseLedgerFromWorkflowArtifacts(workflow.artifacts)
    val reopened = LinkedHashMap(records).apply { put(phaseId, blocked.asPendingForOperatorResume()) }
    val now = clock.instant().atOffset(ZoneOffset.UTC).toString()
    val retry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
        sequenceNumber = Math.addExact(ledger.maxOfOrNull { it.sequenceNumber } ?: -1, 1),
        timestamp = now,
        phaseId = phaseId,
        attemptCount = blocked.attemptCount,
        resolvedAgentId = blocked.resolvedAgentId,
        recoveryAttemptId = admittedAttempt.attemptId,
        preserveBudget = true,
      )
    return WorkflowUpdateInput(
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = phaseId,
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf(
              SharedPayloadKeys.STEP_ID to phaseId,
              SharedPayloadKeys.STATUS to WorkflowStepStatus.PENDING.wireValue,
              FeatureTaskRuntimePhasePayloadKeys.ATTEMPT_COUNT to blocked.attemptCount,
            ),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
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
  }

  private companion object {
    const val MAX_RECOVERY_REASON_CHARS = 256
  }
}

internal data class StandaloneIncidentRecoveryRequest(
  val invocationId: String,
  val phaseId: String,
  val reason: String,
  val admittedAttempt: BlockIncidentRecoveryAttempt,
  val expectedIdentity: FeatureTaskExecutionIdentity,
  val expectedPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
)
