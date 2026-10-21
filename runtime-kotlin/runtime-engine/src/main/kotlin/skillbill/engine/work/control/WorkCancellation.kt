package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.model.GoalRunnerStopStatus
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.observation.workerOwnership
import skillbill.engine.work.supersededPhase
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.isTerminal
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

@Inject
class WorkCancellation(
  private val database: DatabaseSessionFactory,
  private val status: IdeStatusService,
  private val goals: GoalRunnerStatusService,
  private val supervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val clock: Clock,
  private val launcher: WorkRunLauncher,
) {
  fun cancel(target: WorkRunTarget): WorkControlResult {
    val item =
      database.read { matchingRun(status, target) }
        ?: return WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
    if (item.snapshot.lifecycleState in setOf(IdeStatusLifecycleState.TERMINAL, IdeStatusLifecycleState.CANCELED)) {
      return WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
    }
    val goal = item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL
    val interrupted = if (goal) stopGoal(item, target) else interrupt(target)
    if (interrupted is Interruption.Refused) return WorkControlResult.Rejected(interrupted.reason)
    return finish(target, goal, interrupted)
  }

  private fun stopGoal(
    item: WorkDetailItem,
    target: WorkRunTarget,
  ): Interruption {
    val stopped =
      goals.stop(
        requireNotNull(item.snapshot.issueKey),
        target.repositoryRoot,
        item.snapshot.workflowId,
        target.databaseFileObservation,
      ) { matchingRun(status, target) != null }
    val refused = stopGoalRefusal(stopped.status, item.snapshot.lifecycleState)
    return if (refused != null) Interruption.Refused(refused) else Interruption.Unowned
  }

  private fun stopGoalRefusal(
    status: GoalRunnerStopStatus,
    lifecycle: IdeStatusLifecycleState,
  ): WorkControlRejection? =
    goalRunnerStopWorkControlRejection(status)
      ?: when {
        status == GoalRunnerStopStatus.CHANGED_TARGET -> WorkControlRejection.CHANGED_TARGET
        status == GoalRunnerStopStatus.TERMINATION_INCOMPLETE -> WorkControlRejection.TERMINATION_INCOMPLETE
        status == GoalRunnerStopStatus.NOT_FOUND && lifecycle == IdeStatusLifecycleState.ACTIVE ->
          WorkControlRejection.OWNERSHIP_MISMATCH
        else -> null
      }

  private fun finish(
    target: WorkRunTarget,
    goal: Boolean,
    interrupted: Interruption,
  ): WorkControlResult {
    val operation = operation@{ unit: UnitOfWork ->
      if (!stopGoalHarness(goal, target, unit)) {
        return@operation WorkControlResult.Rejected(WorkControlRejection.TERMINATION_INCOMPLETE)
      }
      val current =
        status.detail(WorkDetailRequest(target.id)).item
          ?: return@operation WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
      val identityMatches =
        current.snapshot.repositoryIdentity == target.repositoryIdentity &&
          current.snapshot.execution?.executionId == target.executionId
      val revisionMatches = goal || interrupted is Interruption.Reserved || matchingRun(status, target) != null
      if (!identityMatches || !revisionMatches) {
        return@operation WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      }
      if (interrupted is Interruption.Reserved && ownerChanged(target, current, interrupted.ownership)) {
        return@operation WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      }
      if (goal && current.goalStatus?.executionLiveness == ExecutionLiveness.LIVE) {
        return@operation WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      }
      settle(current, target)
    }
    return when (val result = database.transactionFenced(target.databaseFileObservation, operation)) {
      FencedTransactionResult.ChangedTarget -> WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      is FencedTransactionResult.Committed -> result.value
    }
  }

  private fun stopGoalHarness(
    goal: Boolean,
    target: WorkRunTarget,
    unit: UnitOfWork,
  ): Boolean {
    if (!goal) return true
    val item = status.detail(WorkDetailRequest(target.id)).item ?: return false
    return launcher.stop(WorkRunStopRequest(unit.dbPath, workRunOwnershipKey(item, null))) !is WorkRunStopResult.Refused
  }

  private fun ownerChanged(
    target: WorkRunTarget,
    item: WorkDetailItem,
    expected: FeatureTaskRuntimeWorkerOwnership,
  ): Boolean {
    val owner = unitOwner(target.id, item.snapshot.workflowId) ?: return false
    return owner.ownerToken != expected.ownerToken || owner.generation != expected.generation
  }

  private fun interrupt(target: WorkRunTarget): Interruption {
    val reservation = reserve(target)
    val value =
      when (reservation) {
        FencedTransactionResult.ChangedTarget -> return Interruption.Refused(WorkControlRejection.CHANGED_TARGET)
        is FencedTransactionResult.Committed -> reservation.value
      }
    if (value !is Interruption.Reserved) return value
    val worker = value.ownership
    if (supervisor.inspect(worker) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      supervisor.terminateGracefully(worker)
      supervisor.awaitExit(worker, TERMINATION_GRACE)
    }
    if (supervisor.inspect(worker) == FeatureTaskRuntimeProcessInspection.ExactLive) {
      supervisor.terminateForcibly(worker)
      supervisor.awaitExit(worker, TERMINATION_GRACE)
    }
    return if (supervisor.inspect(worker) == FeatureTaskRuntimeProcessInspection.NotRunning) {
      value
    } else {
      Interruption.Refused(WorkControlRejection.TERMINATION_INCOMPLETE)
    }
  }

  private fun reserve(target: WorkRunTarget): FencedTransactionResult<Interruption> =
    database.transactionFenced(target.databaseFileObservation) { unit ->
      val item =
        matchingRun(status, target)
          ?: return@transactionFenced Interruption.Refused(WorkControlRejection.CHANGED_TARGET)
      val phase = unit.standalonePhaseStatuses.readByInvocation(target.id)
      val workflowId = item.snapshot.workflowId
      val worker =
        phase?.workerOwnership()
          ?: workflowId?.let(unit.workflowStates::getFeatureTaskRuntimeWorkerOwnership)
      if (worker == null && item.snapshot.lifecycleState == IdeStatusLifecycleState.ACTIVE) {
        return@transactionFenced Interruption.Refused(WorkControlRejection.OWNERSHIP_MISMATCH)
      }
      val inspection = worker?.let(supervisor::inspect) ?: FeatureTaskRuntimeProcessInspection.NotRunning
      val interruption = reservedInterruption(phase, worker, inspection, unit)
      if (interruption is Interruption.Refused) return@transactionFenced interruption
      if (supersededPhase(phase, unit, clock.instant())) return@transactionFenced interruption
      val stopped = launcher.stop(WorkRunStopRequest(unit.dbPath, workRunOwnershipKey(item, phase)))
      if (stopped is WorkRunStopResult.Refused) {
        Interruption.Refused(WorkControlRejection.TERMINATION_INCOMPLETE)
      } else {
        interruption
      }
    }

  private fun reservedInterruption(
    phase: StandalonePhaseStatusRecord?,
    worker: FeatureTaskRuntimeWorkerOwnership?,
    inspection: FeatureTaskRuntimeProcessInspection,
    unit: UnitOfWork,
  ): Interruption {
    val inspectionRejection = processInspectionCancelRejection(inspection)
    return when {
      inspectionRejection != null -> Interruption.Refused(inspectionRejection)
      inspection == FeatureTaskRuntimeProcessInspection.NotRunning -> Interruption.Unowned
      inspection == FeatureTaskRuntimeProcessInspection.ExactLive -> {
        requireNotNull(worker)
        val reserved =
          if (phase != null) {
            unit.standalonePhaseStatuses.requestCancellation(phase, clock.instant()) ==
              StandalonePhaseStatusUpdateResult.ACCEPTED
          } else {
            unit.workflowStates.reserveFeatureTaskRuntimeWorkerTakeover(
              worker.workflowId,
              worker.ownerToken,
              worker.generation,
            )
          }
        if (reserved) Interruption.Reserved(worker) else Interruption.Refused(WorkControlRejection.CHANGED_TARGET)
      }
      else -> Interruption.Unowned
    }
  }

  private companion object {
    val TERMINATION_GRACE: Duration = Duration.ofSeconds(5)
  }

  private sealed interface Interruption {
    data object Unowned : Interruption

    data class Reserved(val ownership: FeatureTaskRuntimeWorkerOwnership) : Interruption

    data class Refused(val reason: WorkControlRejection) : Interruption
  }

  private fun unitOwner(
    id: String,
    workflowId: String?,
  ): FeatureTaskRuntimeWorkerOwnership? =
    database.read { unit ->
      unit.standalonePhaseStatuses.readByInvocation(id)?.workerOwnership()
        ?: workflowId?.let(unit.workflowStates::getFeatureTaskRuntimeWorkerOwnership)
    }

  private fun settle(
    item: WorkDetailItem,
    target: WorkRunTarget,
  ): WorkControlResult =
    database.transaction { unit ->
      val phase = unit.standalonePhaseStatuses.readByInvocation(target.id)
      val workflowId = item.snapshot.workflowId
      val ownership =
        phase?.workerOwnership()
          ?: workflowId?.let(unit.workflowStates::getFeatureTaskRuntimeWorkerOwnership)
      if (ownership != null) {
        when (val rejection = processInspectionWorkControlRejection(supervisor.inspect(ownership))) {
          null -> Unit
          else -> return@transaction WorkControlResult.Rejected(rejection)
        }
      }
      val childId = item.goalStatus?.currentChildWorkflowId
      val childOwner = childId?.let(unit.workflowStates::getFeatureTaskRuntimeWorkerOwnership)
      if (childOwner != null) {
        when (val rejection = processInspectionWorkControlRejection(supervisor.inspect(childOwner))) {
          null -> Unit
          else -> return@transaction WorkControlResult.Rejected(rejection)
        }
      }
      val now = clock.instant()
      if (phase != null) {
        val result = unit.standalonePhaseStatuses.cancel(phase, now)
        if (result != StandalonePhaseStatusUpdateResult.ACCEPTED) {
          return@transaction WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
        }
      }
      if (workflowId != null) return@transaction cancelWorkflow(unit, item)
      WorkControlResult.Accepted
    }

  private fun cancelWorkflow(
    unit: UnitOfWork,
    item: WorkDetailItem,
  ): WorkControlResult {
    val workflowId = requireNotNull(item.snapshot.workflowId)
    val now = clock.instant()
    val family =
      if (item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_VERIFY) {
        WorkflowFamily.VERIFY
      } else {
        WorkflowFamily.TASK_RUNTIME
      }
    if (item.snapshot.workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL &&
      !unit.lifecycleTelemetry.cancelGoalIssue(workflowId, requireNotNull(item.snapshot.issueKey), now)
    ) {
      return WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
    }
    item.goalStatus?.currentChildWorkflowId?.let { id ->
      val child = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, id)
      if (child != null && !child.workflowStatus.isTerminal) {
        unit.workflowStates.save(
          WorkflowFamily.TASK_RUNTIME,
          child.copy(workflowStatus = WorkflowStatus.ABANDONED, updatedAt = now, finishedAt = now),
        )
      }
    }
    val workflow = unit.workflowStates.get(family, workflowId)
    if (workflow != null && !workflow.workflowStatus.isTerminal) {
      unit.workflowStates.save(
        family,
        workflow.copy(workflowStatus = WorkflowStatus.ABANDONED, updatedAt = now, finishedAt = now),
      )
    }
    return WorkControlResult.Accepted
  }
}

internal fun matchingRun(
  status: IdeStatusService,
  target: WorkRunTarget,
): WorkDetailItem? =
  status.detail(WorkDetailRequest(target.id)).item?.takeIf {
    it.snapshot.repositoryIdentity == target.repositoryIdentity &&
      Path.of(it.repositoryRoot).toAbsolutePath().normalize() == target.repositoryRoot.toAbsolutePath().normalize() &&
      it.snapshot.execution?.executionId == target.executionId &&
      it.snapshot.execution?.statusRevision == target.statusRevision
  }

internal fun workRunOwnershipKey(
  item: WorkDetailItem,
  phase: StandalonePhaseStatusRecord?,
): String =
  item.snapshot.workflowId ?: listOf(
    item.snapshot.repositoryIdentity,
    phase?.monitorTarget?.pullRequestKey ?: item.snapshot.branchCorrelation,
    phase?.phaseId,
  ).joinToString(":")
