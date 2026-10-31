package skillbill.engine.featuretask.incident.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.report.ProductReportDelivery
import skillbill.engine.diagnostics.containableUnlessInterrupted
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.blockincident.BlockIncidentTelemetryPort
import skillbill.ports.blockincident.model.BlockIncidentRecoveryTransition
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.time.Clock
import java.time.Instant

@Inject
class BlockIncidentRecoveryInterruptedReconciliation(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val reportDelivery: ProductReportDelivery?,
  private val telemetry: BlockIncidentTelemetryPort?,
) {
  fun reconcile(limit: Int = RECONCILE_BATCH): Int {
    val batch = limit.coerceIn(1, MAX_BATCH)
    val interrupted =
      catchingNonCancellation {
        database.read { it.blockIncidentRecoveries.interrupted(clock.instant(), batch) }
      }.getOrElse { failure ->
        containableUnlessInterrupted(failure)
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Interrupted block incident recovery scan failed; continuing the run.",
          failure,
        )
        emptyList()
      }
    return interrupted.count { attempt ->
      val settled =
        catchingNonCancellation {
          database.transaction { unit -> settle(unit, attempt, clock.instant()) }
        }.getOrElse { failure ->
          containableUnlessInterrupted(failure)
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Interrupted recovery attempt '${attempt.attemptId}' could not be settled; continuing the run.",
            failure,
          )
          null
        }
      if (settled != null) {
        catchingNonCancellation {
          val occurrence = database.read { unit -> unit.blockIncidents.find(settled.occurrenceToken)?.occurrence }
          if (occurrence == null) {
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "Interrupted recovery telemetry skipped because incident context is missing.",
            )
          } else {
            telemetry?.recovery(occurrence, settled)
          }
        }.getOrElse { failure ->
          containableUnlessInterrupted(failure)
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Interrupted recovery telemetry failed for '${settled.attemptId}'; continuing the run.",
            failure,
          )
        }
        catchingNonCancellation {
          reportDelivery?.recoverySettled(settled, dispatch = false)
        }.getOrElse { failure ->
          containableUnlessInterrupted(failure)
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Interrupted recovery report notification failed for '${settled.attemptId}'; continuing the run.",
            failure,
          )
        }
      }
      settled != null
    }
  }

  private fun settle(
    unit: UnitOfWork,
    observed: BlockIncidentRecoveryAttempt,
    now: Instant,
  ): BlockIncidentRecoveryAttempt? {
    val stored =
      unit.blockIncidentRecoveries.byIdempotencyKey(observed.idempotencyKey)
        ?.takeIf { it.isStillInterrupted(observed, now) }
        ?: return null
    val incident = unit.blockIncidents.find(stored.occurrenceToken)
    val next = interruptedStatus(unit, incident, stored, now)
    val moved =
      unit.blockIncidentRecoveries.transition(
        BlockIncidentRecoveryTransition(
          stored.attemptId,
          stored.status,
          next,
          if (next == BlockIncidentRecoveryAttemptStatus.FAILED) BlockIncidentRecoveryFailure.INTERRUPTED else null,
          stored.ownerToken,
          stored.ownerGeneration,
          if (next == BlockIncidentRecoveryAttemptStatus.APPLIED) stored.ownerExpiresAt else null,
          now,
        ),
      )
    if (!moved) return null
    val outcome =
      when (next) {
        BlockIncidentRecoveryAttemptStatus.FAILED -> BlockIncidentRecovery.FAILED
        BlockIncidentRecoveryAttemptStatus.RESUMED -> BlockIncidentRecovery.COMPLETE
        else -> null
      }
    if (incident != null && outcome != null) recordBlockIncidentRecoveryOutcome(unit, incident, outcome)
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Interrupted block incident recovery attempt '${stored.attemptId}' settled from " +
        "${stored.status.wireValue} to ${next.wireValue} for occurrence '${stored.occurrenceToken}'" +
        if (incident == null) "; the incident was missing, so no recovery outcome was recorded." else ".",
    )
    return if (outcome == null) null else unit.blockIncidentRecoveries.byIdempotencyKey(stored.idempotencyKey)
  }

  private fun interruptedStatus(
    unit: UnitOfWork,
    incident: BlockIncident?,
    attempt: BlockIncidentRecoveryAttempt,
    now: Instant,
  ): BlockIncidentRecoveryAttemptStatus =
    when {
      incident == null || !blockCleared(unit, incident) || !runActiveOrCompleted(unit, incident, attempt, now) ->
        BlockIncidentRecoveryAttemptStatus.FAILED
      attempt.status == BlockIncidentRecoveryAttemptStatus.APPLYING -> BlockIncidentRecoveryAttemptStatus.APPLIED
      else -> BlockIncidentRecoveryAttemptStatus.RESUMED
    }

  private fun BlockIncidentRecoveryAttempt.isStillInterrupted(
    observed: BlockIncidentRecoveryAttempt,
    now: Instant,
  ): Boolean =
    status in BlockIncidentRecoveryAttemptStatus.OWNED &&
      status == observed.status &&
      ownerToken == observed.ownerToken &&
      ownerExpiresAt?.isBefore(now) == true

  private fun blockCleared(
    unit: UnitOfWork,
    incident: BlockIncident,
  ): Boolean {
    val workflowId = incident.occurrence.workflowId ?: return false
    val phaseId = incident.occurrence.phaseId ?: return false
    val workflow = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
    val step = workflow?.steps?.firstOrNull { it.stepId == phaseId }
    val phase = workflow?.let { decodePhaseRecords(it.artifacts)[phaseId] }
    return workflow != null && workflow.workflowStatus != WorkflowStatus.BLOCKED &&
      phaseCleared(step?.status, phase?.status)
  }

  private fun phaseCleared(
    step: WorkflowStepStatus?,
    phase: WorkflowStepStatus?,
  ): Boolean =
    step != null && phase != null && step != WorkflowStepStatus.BLOCKED && phase != WorkflowStepStatus.BLOCKED

  private fun runActiveOrCompleted(
    unit: UnitOfWork,
    incident: BlockIncident,
    attempt: BlockIncidentRecoveryAttempt,
    now: Instant,
  ): Boolean {
    val workflowIds = listOfNotNull(incident.occurrence.workflowId, attempt.targetWorkflowId).distinct()
    val completed =
      workflowIds.any {
        unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, it)?.workflowStatus == WorkflowStatus.COMPLETED
      }
    val newerGeneration =
      attempt.ownerGeneration?.let { verified ->
        workflowIds.any {
          (
            unit.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(
              it,
            )?.generation ?: 0L
          ) > verified
        }
      } == true
    val liveWorker =
      workflowIds.any { workflowId ->
        unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)?.let { ownership ->
          ownership.expiresAtInstant.isAfter(now) &&
            workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.NotRunning
        } == true
      }
    val liveGoalLease =
      workflowIds.any {
        unit.goalRunnerControls.controlState(it).executionLease?.expiresAtInstant?.isAfter(now) == true
      }
    val liveInvocation =
      attempt.targetInvocationId?.let {
        unit.standalonePhaseStatuses.readByInvocation(it)?.leaseExpiresAt?.isAfter(now)
      } == true
    return completed || newerGeneration || liveWorker || liveGoalLease || liveInvocation
  }

  private companion object {
    const val RECONCILE_BATCH = 16
    const val MAX_BATCH = 100
  }
}
