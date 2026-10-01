package skillbill.engine.goalrunner.persist

import skillbill.engine.goalrunner.model.CrashReconcileExpiredWorkerRequest
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.taskruntime.model.isConfirmedDead
import java.time.Clock

internal fun crashReconcileExpiredWorkerToResumable(
  request: CrashReconcileExpiredWorkerRequest,
  clock: Clock,
): GoalRunnerStoredOutcome? {
  val now = clock.instant()
  if (!request.ownership.expiresAtInstant.isBefore(now)) return null
  if (!request.workerSupervisor.inspect(request.ownership).isConfirmedDead()) return null
  val reconciled =
    request.workflowStates.reconcileFeatureTaskRuntimeCrashedWorker(
      workflowId = request.workflowId,
      ownerToken = request.ownership.ownerToken,
      generation = request.ownership.generation,
      interruptionReason = "lease_expired: worker lease expired and process confirmed dead",
      nowInstant = now.toString(),
    )
  if (!reconciled) return null
  return GoalRunnerStoredOutcome(
    status = GoalRunnerTerminalStatus.RECONCILABLE,
    workflowId = request.workflowId,
    commitSha = null,
    blockedReason = null,
    lastResumableStep = request.row.currentStepId.ifBlank { "preplan" },
    suppressPr = request.continuation.suppressPr,
  )
}
