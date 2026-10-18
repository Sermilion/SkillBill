package skillbill.engine.featuretask.phase.record

import skillbill.engine.featuretask.lifecycle.execution.requireCurrent
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Clock

internal val WORKER_TERMINAL_STATUSES =
  setOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.ABANDONED)

internal fun requireCurrentWorker(
  admitted: AdmittedFeatureTaskRuntimeExecution,
  states: WorkflowStateRepository,
  workflowId: String,
  clock: Clock,
): Boolean {
  admitted.requireCurrent(states, workflowId)
  val expected = admitted.workerOwnership ?: return true
  val workflow = states.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
  val current = states.getFeatureTaskRuntimeWorkerOwnership(workflowId)
  if (
    workflow == null ||
    current == null ||
    workflow.workflowStatus.workflowStatus() in WORKER_TERMINAL_STATUSES
  ) {
    return false
  }
  return current.ownerToken == expected.ownerToken &&
    current.generation == expected.generation &&
    current.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE &&
    current.expiresAtInstant > clock.instant()
}

internal fun phaseEventAdmissible(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord?,
  phaseStart: Boolean,
): Boolean {
  if (previous == null) {
    val status = request.status.workflowStepStatus()
    return status == WorkflowStepStatus.RUNNING ||
      status == WorkflowStepStatus.BLOCKED ||
      (!phaseStart && status == WorkflowStepStatus.COMPLETED && request.finished)
  }
  if (request.attemptCount < previous.attemptCount) return false
  if (request.attemptCount > previous.attemptCount) {
    val status = request.status.workflowStepStatus()
    return status == WorkflowStepStatus.RUNNING ||
      (!phaseStart && status == WorkflowStepStatus.BLOCKED)
  }
  return previous.status != WorkflowStepStatus.COMPLETED || completedPhaseRequestMatches(request, previous)
}

internal fun completedPhaseRequestMatches(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord,
): Boolean =
  request.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED &&
    request.finished &&
    request.attemptCount == previous.attemptCount &&
    request.resolvedAgentId == previous.resolvedAgentId &&
    (request.outputArtifact ?: previous.outputArtifact) == previous.outputArtifact &&
    request.rejectedOutput == previous.rejectedOutput &&
    request.blockedReason == previous.blockedReason &&
    request.failureDisposition == previous.failureDisposition &&
    completedPhaseManifestMatches(request, previous)

private fun completedPhaseManifestMatches(
  request: FeatureTaskRuntimePhaseStateRequest,
  previous: FeatureTaskRuntimePhaseRecord,
): Boolean =
  request.fileManifestBefore == previous.fileManifestBefore &&
    request.fileManifestAfter == previous.fileManifestAfter &&
    request.fileManifestIntroduced == previous.fileManifestIntroduced &&
    request.loopId == previous.loopId &&
    request.edgeIteration == previous.edgeIteration &&
    request.reviewPassNumber == previous.reviewPassNumber &&
    request.repairEvidence == previous.repairEvidence

internal fun checkpointIdentityWriteAdmissible(
  attemptCount: Int,
  preceding: FeatureTaskRuntimePhaseRecord?,
): Boolean = preceding != null && attemptCount >= preceding.attemptCount

internal fun phaseCoupledEvidenceWriteAdmissible(
  attemptCount: Int,
  previous: FeatureTaskRuntimePhaseRecord?,
): Boolean {
  if (previous == null) return false
  if (previous.status == WorkflowStepStatus.COMPLETED) return false
  if (attemptCount < previous.attemptCount) return false
  return true
}
