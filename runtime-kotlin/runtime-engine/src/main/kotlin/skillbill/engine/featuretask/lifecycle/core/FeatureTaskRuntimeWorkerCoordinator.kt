package skillbill.engine.featuretask.lifecycle.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.truncateWalBestEffort
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseCheckpoint
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import java.time.Clock
import java.time.Duration
import java.util.UUID

@RuntimeSingleton
@Inject
class FeatureTaskRuntimeWorkerCoordinator(
  private val database: DatabaseSessionFactory,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
  private val executionAdmission: FeatureTaskRuntimeExecutionAdmission,
  private val diagnostics: RuntimeDiagnostics,
  private val checkpoint: DatabaseCheckpoint,
) {
  fun <T> runOwned(
    workflowId: String,
    effectiveInputs: EffectiveGatePolicyInputs,
    expectedIdentity: FeatureTaskExecutionIdentity,
    requestedReviewSelection: RuntimeReviewSelection? = null,
    block: (AdmittedFeatureTaskRuntimeExecution, AcquiredFeatureTaskRuntimeWorker) -> T,
  ): T {
    val acquired = acquireOrRecover(workflowId, effectiveInputs, expectedIdentity, requestedReviewSelection)
    val ownership = acquired.ownership
    val heartbeats = supervisor.startHeartbeat(heartbeatPlan(workflowId)) { heartbeat(ownership) }
    val writer =
      AcquiredFeatureTaskRuntimeWorker(
        ownership,
        acquired.execution,
        acquired.original,
        acquired.previousOwnerGeneration,
        acquired.previousOwnerStopped,
        acquired.execution.effectiveInputs,
        acquired.admittedSnapshot,
      )
    val result =
      try {
        block(acquired.execution.withWorkerOwnership(ownership), writer)
      } finally {
        writer.close()
        heartbeats.stop()
        val releasedAndIdle =
          database.transaction {
            val released =
              it.workflowStates.releaseFeatureTaskRuntimeWorker(
                workflowId,
                ownership.ownerToken,
                ownership.generation,
              )
            released && !it.workflowStates.hasActiveFeatureTaskRuntimeWorkerLeases()
          }
        if (releasedAndIdle) checkpoint.truncateWalBestEffort(diagnostics, "worker_lease_idle")
      }

    heartbeats.fencingLostReason()?.let { reason ->
      error("Worker for workflow '$workflowId' lost lease fencing mid-phase: $reason")
    }
    return result
  }

  private fun heartbeatPlan(workflowId: String) =
    FeatureTaskRuntimeHeartbeatPlan(
      label = workflowId,
      intervalSeconds = HEARTBEAT_SECONDS,
      leaseSeconds = LEASE_DURATION.seconds,
    )

  private fun acquireOrRecover(
    workflowId: String,
    inputs: EffectiveGatePolicyInputs,
    identity: FeatureTaskExecutionIdentity,
    reviewSelection: RuntimeReviewSelection?,
  ): AcquiredWorker {
    val existing = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) }
    return if (existing == null) {
      acquireUnowned(workflowId, inputs, identity, reviewSelection)
    } else {
      recoverOwned(existing, inputs, identity, reviewSelection)
    }
  }

  private fun acquireUnowned(
    workflowId: String,
    inputs: EffectiveGatePolicyInputs,
    identity: FeatureTaskExecutionIdentity,
    reviewSelection: RuntimeReviewSelection?,
  ): AcquiredWorker {
    repeat(UNOWNED_ACQUIRE_ATTEMPTS) {
      when (val claim = claimUnowned(workflowId, inputs, identity, reviewSelection)) {
        is UnownedClaim.Owned -> return claim.acquired
        is UnownedClaim.Recover -> return recoverOwned(claim.existing, inputs, identity, reviewSelection)
        is UnownedClaim.Lost -> Unit
      }
    }
    error("Workflow '$workflowId' changed before worker ownership could be acquired.")
  }

  private fun claimUnowned(
    workflowId: String,
    inputs: EffectiveGatePolicyInputs,
    identity: FeatureTaskExecutionIdentity,
    reviewSelection: RuntimeReviewSelection?,
  ): UnownedClaim {
    var receipt: RuntimeMigrationReceipt? = null
    return runCatching {
      database.transaction { unitOfWork ->
        val existing = unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId)
        if (existing != null) return@transaction UnownedClaim.Recover(existing)
        val row =
          unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
            ?: throw invalidWorkflowStateSchemaError("Feature-task runtime worker workflow '$workflowId' is missing.")
        if (row.workflowStatus.workflowStatus() in TERMINAL_WORKFLOW_STATUSES) {
          error(
            "Cannot acquire worker ownership for terminal workflow '$workflowId' (${row.workflowStatus}).",
          )
        }
        val execution =
          executionAdmission.admit(
            unitOfWork,
            workflowId,
            inputs,
            identity,
            reviewSelection,
          )
        receipt = execution.migrationReceipt
        val admittedRow =
          unitOfWork.workflowStates.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
            ?: throw invalidWorkflowStateSchemaError(
              "Feature-task runtime worker workflow '$workflowId' disappeared during admission.",
            )
        val admittedSnapshot = admittedRow.toSnapshot()
        val priorGeneration =
          maxOf(
            unitOfWork.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(workflowId)?.generation ?: 0L,
            BlockIncidentIdentity.fromArtifacts(admittedSnapshot.artifacts)?.generation ?: 0L,
          )
        val currentPhaseAttempt = currentPhaseAttempt(admittedSnapshot)
        val ownership =
          newOwnership(
            workflowId,
            generation = Math.addExact(priorGeneration, 1L),
            phaseId = admittedRow.currentStepId,
            phaseAttempt = currentPhaseAttempt,
          )
        if (unitOfWork.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, admittedRow.updatedAt)) {
          UnownedClaim.Owned(AcquiredWorker(ownership, execution, row.toSnapshot(), 0, false, admittedSnapshot))
        } else {
          UnownedClaim.Lost(execution)
        }
      }
    }.onFailure {
      receipt?.let { executionAdmission.recordTransactionOutcome(it, committed = false) }
    }.getOrThrow().also {
      receipt?.let { executionAdmission.recordTransactionOutcome(it, committed = true) }
    }
  }

  private fun currentPhaseAttempt(snapshot: WorkflowStateSnapshot): Int =
    snapshot.step(snapshot.currentStepId)?.attemptCount?.coerceAtLeast(1)
      ?: throw invalidWorkflowStateSchemaError(
        "Feature-task runtime worker workflow '${snapshot.workflowId}' has no current phase attempt.",
      )

  private fun recoverOwned(
    existing: FeatureTaskRuntimeWorkerOwnership,
    inputs: EffectiveGatePolicyInputs,
    identity: FeatureTaskExecutionIdentity,
    reviewSelection: RuntimeReviewSelection?,
  ): AcquiredWorker {
    val inspection = inspectForRecovery(existing)
    if (inspection == FeatureTaskRuntimeProcessInspection.ExactLive &&
      !existing.expiresAtInstant.isAfter(clock.instant())
    ) {
      diagnostics.info(
        "seam=feature-task-runtime.worker-recovery workflow_id=${existing.workflowId} " +
          "outcome=live_expired_lease owner_token=${existing.ownerToken}",
      )
    }
    val (admitted, original) = admitWithTakeoverReservation(existing, inputs, identity, reviewSelection)
    if (inspection == FeatureTaskRuntimeProcessInspection.ExactLive) stopExactWorker(existing)
    val previousOwnerStopped = supervisor.inspect(existing) == FeatureTaskRuntimeProcessInspection.NotRunning
    return transferOwned(existing, admitted, original, previousOwnerStopped)
  }

  private fun transferOwned(
    existing: FeatureTaskRuntimeWorkerOwnership,
    admitted: AdmittedFeatureTaskRuntimeExecution,
    original: WorkflowStateSnapshot,
    previousOwnerStopped: Boolean,
  ): AcquiredWorker {
    val (transferred, admittedSnapshot, replacement) = admitWithOwnershipTransfer(existing, admitted)
    return AcquiredWorker(
      replacement,
      transferred,
      original,
      existing.generation,
      previousOwnerStopped,
      admittedSnapshot,
    )
  }

  private fun admitWithTakeoverReservation(
    existing: FeatureTaskRuntimeWorkerOwnership,
    inputs: EffectiveGatePolicyInputs,
    identity: FeatureTaskExecutionIdentity,
    reviewSelection: RuntimeReviewSelection?,
  ): Pair<AdmittedFeatureTaskRuntimeExecution, WorkflowStateSnapshot> {
    var admissionReceipt: RuntimeMigrationReceipt? = null
    return runCatching {
      database.transaction {
        val original =
          requireNotNull(
            it.workflowStates.getFeatureTaskWorkflowAsMode(
              existing.workflowId,
              FeatureTaskWorkflowMode.RUNTIME,
            )?.toSnapshot(),
          )
        val execution =
          executionAdmission.admit(
            it,
            existing.workflowId,
            inputs,
            identity,
            reviewSelection,
          )
        admissionReceipt = execution.migrationReceipt
        val retainedGeneration =
          it.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(existing.workflowId)?.generation
        if (retainedGeneration != existing.generation) {
          error("Worker generation observation changed for workflow '${existing.workflowId}'.")
        }
        val reserved =
          it.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
            existing.workflowId,
            existing.ownerToken,
            existing.generation,
          )
        if (!reserved) {
          val current = it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(existing.workflowId)
          val leftoverReservation =
            existing.leaseState == FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED &&
              current != null &&
              current.workflowId == existing.workflowId &&
              current.ownerToken == existing.ownerToken &&
              current.generation == existing.generation &&
              current.leaseState == FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED
          if (!leftoverReservation) {
            error("Concurrent continuation already claimed workflow '${existing.workflowId}'.")
          }
          diagnostics.info(
            "seam=feature-task-runtime.worker-recovery workflow_id=${existing.workflowId} " +
              "outcome=leftover_takeover_reserved owner_token=${existing.ownerToken}",
          )
        }
        execution to original
      }
    }.onFailure {
      admissionReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = false) }
    }.getOrThrow().also {
      admissionReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = true) }
    }
  }

  private fun admitWithOwnershipTransfer(
    existing: FeatureTaskRuntimeWorkerOwnership,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Triple<AdmittedFeatureTaskRuntimeExecution, WorkflowStateSnapshot, FeatureTaskRuntimeWorkerOwnership> {
    var transferReceipt: RuntimeMigrationReceipt? = null
    return runCatching {
      database.transaction {
        val execution =
          executionAdmission.admit(
            it,
            existing.workflowId,
            admitted.effectiveInputs,
            admitted.identity,
            admitted.plan.reviewSelection,
          )
        transferReceipt = execution.migrationReceipt
        val admittedSnapshot = requireNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, existing.workflowId))
        val currentPhaseAttempt =
          admittedSnapshot.step(admittedSnapshot.currentStepId)
            ?.attemptCount
            ?.coerceAtLeast(1)
            ?: throw invalidWorkflowStateSchemaError(
              "Feature-task runtime worker workflow '${existing.workflowId}' has no current phase attempt.",
            )
        val replacement =
          newOwnership(
            existing.workflowId,
            Math.addExact(existing.generation, 1L),
            admittedSnapshot.currentStepId,
            currentPhaseAttempt,
          )
        if (!it.workflowStates.transferFeatureTaskRuntimeWorker(
            replacement,
            existing.ownerToken,
            existing.generation,
          )
        ) {
          error("Worker takeover fencing changed for workflow '${existing.workflowId}'.")
        }
        Triple(execution, admittedSnapshot, replacement)
      }
    }.onFailure {
      transferReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = false) }
    }.getOrThrow().also {
      transferReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = true) }
    }
  }

  private fun inspectForRecovery(existing: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection {
    val inspection = supervisor.inspect(existing)
    when (inspection) {
      FeatureTaskRuntimeProcessInspection.ExactLive -> Unit
      FeatureTaskRuntimeProcessInspection.NotRunning -> Unit
      FeatureTaskRuntimeProcessInspection.ForeignHost ->
        if (leaseIsActive(existing)) {
          error("Worker ownership for workflow '${existing.workflowId}' belongs to a different host.")
        }
      FeatureTaskRuntimeProcessInspection.PidReused ->
        if (leaseIsActive(existing)) {
          error("Worker ownership for workflow '${existing.workflowId}' PID was reused.")
        }
      is FeatureTaskRuntimeProcessInspection.Unsupported ->
        if (leaseIsActive(existing)) {
          error("Worker process inspection for workflow '${existing.workflowId}' is unsupported: ${inspection.reason}")
        }
    }
    return inspection
  }

  private fun leaseIsActive(existing: FeatureTaskRuntimeWorkerOwnership): Boolean =
    existing.expiresAtInstant.isAfter(clock.instant())

  private fun stopExactWorker(existing: FeatureTaskRuntimeWorkerOwnership) {
    supervisor.terminateGracefully(existing)
    repeat(GRACE_POLLS) {
      if (supervisor.inspect(existing) == FeatureTaskRuntimeProcessInspection.NotRunning) return
      supervisor.pause(GRACE_POLL_MILLIS)
    }
    if (supervisor.inspect(existing) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      supervisor.terminateForcibly(existing)
    }
    if (supervisor.inspect(existing) != FeatureTaskRuntimeProcessInspection.NotRunning) {
      error("Exact worker for workflow '${existing.workflowId}' could not be stopped safely.")
    }
  }

  private fun heartbeat(base: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeHeartbeatTick {
    val now = clock.instant()
    val updated = base.copy(heartbeatAt = now.toString(), expiresAt = now.plus(LEASE_DURATION).toString())
    return database.transaction { unit ->
      when {
        unit.workflowStates.heartbeatFeatureTaskRuntimeWorker(updated) -> FeatureTaskRuntimeHeartbeatTick.Renewed
        finishedUnderOwnership(unit.workflowStates, base) -> FeatureTaskRuntimeHeartbeatTick.WorkflowFinished
        else ->
          FeatureTaskRuntimeHeartbeatTick.FencingLost(
            "worker lease fencing was lost for workflow '${base.workflowId}'",
          )
      }
    }
  }

  private fun finishedUnderOwnership(
    workflowStates: WorkflowStateRepository,
    base: FeatureTaskRuntimeWorkerOwnership,
  ): Boolean {
    val current = workflowStates.getFeatureTaskRuntimeWorkerOwnership(base.workflowId) ?: return false
    val status = workflowStates.getFeatureTaskWorkflow(base.workflowId)?.workflowStatus ?: return false
    return current.ownerToken == base.ownerToken &&
      current.generation == base.generation &&
      current.leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE &&
      status.workflowStatus() in TERMINAL_WORKFLOW_STATUSES
  }

  private fun newOwnership(
    workflowId: String,
    generation: Long,
    phaseId: String,
    phaseAttempt: Int,
  ): FeatureTaskRuntimeWorkerOwnership {
    val process = supervisor.currentProcess()
    val now = clock.instant()
    return FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      generation = generation,
      ownerToken = UUID.randomUUID().toString(),
      hostIdentity = process.hostIdentity,
      bootIdentity = process.bootIdentity,
      pid = process.pid,
      processBirthToken = process.processBirthToken,
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = now.toString(),
      expiresAt = now.plus(LEASE_DURATION).toString(),
      phaseId = phaseId,
      phaseAttempt = phaseAttempt,
    )
  }

  private companion object {
    val LEASE_DURATION: Duration = Duration.ofSeconds(30)
    const val HEARTBEAT_SECONDS: Long = 10
    const val GRACE_POLLS: Int = 20
    const val GRACE_POLL_MILLIS: Long = 100
    const val UNOWNED_ACQUIRE_ATTEMPTS: Int = 3
    val TERMINAL_WORKFLOW_STATUSES = setOf(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.ABANDONED)
  }

  private data class AcquiredWorker(
    val ownership: FeatureTaskRuntimeWorkerOwnership,
    val execution: AdmittedFeatureTaskRuntimeExecution,
    val original: WorkflowStateSnapshot,
    val previousOwnerGeneration: Long,
    val previousOwnerStopped: Boolean,
    val admittedSnapshot: WorkflowStateSnapshot,
  )

  private sealed class UnownedClaim {
    class Owned(val acquired: AcquiredWorker) : UnownedClaim()

    class Recover(val existing: FeatureTaskRuntimeWorkerOwnership) : UnownedClaim()

    class Lost(val execution: AdmittedFeatureTaskRuntimeExecution) : UnownedClaim()
  }
}
