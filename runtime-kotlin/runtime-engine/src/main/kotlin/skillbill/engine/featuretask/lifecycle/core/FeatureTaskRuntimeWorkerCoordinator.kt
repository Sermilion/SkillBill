package skillbill.engine.featuretask.lifecycle.core

import me.tatarka.inject.annotations.Inject
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
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
) {
  fun <T> runOwned(
    workflowId: String,
    effectiveInputs: EffectiveGatePolicyInputs,
    expectedIdentity: FeatureTaskExecutionIdentity,
    requestedReviewSelection: RuntimeReviewSelection? = null,
    block: (AdmittedFeatureTaskRuntimeExecution) -> T,
  ): T {
    val acquired = acquireOrRecover(workflowId, effectiveInputs, expectedIdentity, requestedReviewSelection)
    val ownership = acquired.ownership
    val heartbeats = supervisor.startHeartbeat(heartbeatPlan(workflowId)) { heartbeat(ownership) }
    val result =
      try {
        block(acquired.execution)
      } finally {
        heartbeats.stop()
        database.transaction {
          it.workflowStates.releaseFeatureTaskRuntimeWorker(workflowId, ownership.ownerToken, ownership.generation)
        }
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
            ?: throw InvalidWorkflowStateSchemaError("Feature-task runtime worker workflow '$workflowId' is missing.")
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
            ?: throw InvalidWorkflowStateSchemaError(
              "Feature-task runtime worker workflow '$workflowId' disappeared during admission.",
            )
        val ownership =
          newOwnership(
            workflowId,
            generation = 1,
            phaseId = admittedRow.currentStepId,
            phaseAttempt = 1,
          )
        if (unitOfWork.workflowStates.acquireFeatureTaskRuntimeWorker(ownership, admittedRow.updatedAt)) {
          UnownedClaim.Owned(AcquiredWorker(ownership, execution))
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

  private fun recoverOwned(
    existing: FeatureTaskRuntimeWorkerOwnership,
    inputs: EffectiveGatePolicyInputs,
    identity: FeatureTaskExecutionIdentity,
    reviewSelection: RuntimeReviewSelection?,
  ): AcquiredWorker {
    val inspection = inspectForRecovery(existing)
    var admissionReceipt: RuntimeMigrationReceipt? = null
    val admitted =
      runCatching {
        database.transaction {
          val execution =
            executionAdmission.admit(
              it,
              existing.workflowId,
              inputs,
              identity,
              reviewSelection,
            )
          admissionReceipt = execution.migrationReceipt
          val reserved =
            it.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
              existing.workflowId,
              existing.ownerToken,
              existing.generation,
            )
          if (!reserved) error("Concurrent continuation already claimed workflow '${existing.workflowId}'.")
          execution
        }
      }.onFailure {
        admissionReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = false) }
      }.getOrThrow().also {
        admissionReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = true) }
      }
    if (inspection == FeatureTaskRuntimeProcessInspection.ExactLive) stopExactWorker(existing)
    val replacement =
      newOwnership(
        existing.workflowId,
        existing.generation + 1,
        existing.phaseId,
        existing.phaseAttempt + 1,
      )
    var transferReceipt: RuntimeMigrationReceipt? = null
    val transferred =
      runCatching {
        database.transaction {
          val execution =
            executionAdmission.admit(
              it,
              existing.workflowId,
              inputs,
              admitted.identity,
              reviewSelection,
            )
          transferReceipt = execution.migrationReceipt
          if (!it.workflowStates.transferFeatureTaskRuntimeWorker(
              replacement,
              existing.ownerToken,
              existing.generation,
            )
          ) {
            error("Worker takeover fencing changed for workflow '${existing.workflowId}'.")
          }
          execution
        }
      }.onFailure {
        transferReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = false) }
      }.getOrThrow().also {
        transferReceipt?.let { executionAdmission.recordTransactionOutcome(it, committed = true) }
      }
    return AcquiredWorker(replacement, transferred)
  }

  private fun inspectForRecovery(existing: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection {
    val inspection = supervisor.inspect(existing)
    when (inspection) {
      FeatureTaskRuntimeProcessInspection.ExactLive -> Unit
      FeatureTaskRuntimeProcessInspection.NotRunning -> Unit
      is FeatureTaskRuntimeProcessInspection.OwnershipMismatch ->
        if (leaseIsActive(existing)) error(inspection.reason)
      is FeatureTaskRuntimeProcessInspection.Unsupported ->
        if (leaseIsActive(existing)) error(inspection.reason)
    }
    return inspection
  }

  private fun leaseIsActive(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    ownership.expiresAtInstant.isAfter(clock.instant())

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
    val persisted =
      database.transaction {
        it.workflowStates.heartbeatFeatureTaskRuntimeWorker(updated)
      }
    return if (persisted) {
      FeatureTaskRuntimeHeartbeatTick.Renewed
    } else {
      FeatureTaskRuntimeHeartbeatTick.FencingLost(
        "worker lease fencing was lost for workflow '${base.workflowId}'",
      )
    }
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
  )

  private sealed class UnownedClaim {
    class Owned(val acquired: AcquiredWorker) : UnownedClaim()

    class Recover(val existing: FeatureTaskRuntimeWorkerOwnership) : UnownedClaim()

    class Lost(val execution: AdmittedFeatureTaskRuntimeExecution) : UnownedClaim()
  }
}
