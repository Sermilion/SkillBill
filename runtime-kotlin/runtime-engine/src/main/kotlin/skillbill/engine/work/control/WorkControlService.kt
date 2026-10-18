package skillbill.engine.work.control

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.model.GoalRunnerPauseStatus
import skillbill.engine.goalrunner.model.GoalRunnerStopStatus
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.work.IdeStatusService
import skillbill.engine.work.control.model.WorkControlAction
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkControlTarget
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.idestatus.model.WorkBoardRequest
import skillbill.ports.persistence.UnitOfWork
import java.nio.file.Path

@Inject
class WorkControlService(
  private val database: DatabaseSessionFactory,
  private val status: IdeStatusService,
  private val controls: GoalRunnerStatusService,
  private val cancellation: WorkCancellation,
  private val runs: WorkRunActions,
) {
  fun options(target: WorkRunTarget): WorkRunOptions? =
    database.read { unit ->
      matchingRun(status, target)?.let { runs.options(it, unit) }
    }

  fun request(
    target: WorkRunTarget,
    action: WorkControlAction,
    agentId: String? = null,
  ): WorkControlResult =
    when (action) {
      WorkControlAction.CANCEL -> cancellation.cancel(target)
      WorkControlAction.LAUNCH -> runs.launch(target, agentId)
      WorkControlAction.PAUSE, WorkControlAction.STOP ->
        WorkControlResult.Rejected(
          WorkControlRejection.INELIGIBLE_TARGET,
        )
    }

  fun request(
    target: WorkControlTarget,
    action: WorkControlAction,
    storeIsCurrent: () -> Boolean = { true },
  ): WorkControlResult {
    if (action !in setOf(WorkControlAction.PAUSE, WorkControlAction.STOP)) {
      return WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
    }
    return if (action == WorkControlAction.STOP) stop(target, storeIsCurrent) else pause(target, storeIsCurrent)
  }

  private fun stop(
    target: WorkControlTarget,
    storeIsCurrent: () -> Boolean,
  ): WorkControlResult {
    var rejection: WorkControlResult.Rejected? = null
    val result =
      controls.stop(
        target.issueKey,
        target.repositoryRoot,
        target.workflowId,
        target.databaseFileObservation,
      ) {
        rejection = validate(target, WorkControlAction.STOP, storeIsCurrent)
        rejection == null
      }
    rejection?.let { return it }
    return when (result.status) {
      GoalRunnerStopStatus.NOT_FOUND -> WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
      GoalRunnerStopStatus.IDENTITY_MISMATCH ->
        if (result.terminationAttempted) {
          WorkControlResult.TerminationAttempted
        } else {
          WorkControlResult.Rejected(WorkControlRejection.IDENTITY_MISMATCH)
        }
      GoalRunnerStopStatus.CHANGED_TARGET -> WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      GoalRunnerStopStatus.TERMINATION_INCOMPLETE ->
        WorkControlResult.Rejected(WorkControlRejection.TERMINATION_INCOMPLETE)
      GoalRunnerStopStatus.STOPPED -> WorkControlResult.ExitConfirmed
      else ->
        goalRunnerStopWorkControlRejection(result.status)?.let { WorkControlResult.Rejected(it) }
          ?: WorkControlResult.Accepted
    }
  }

  private fun pause(
    target: WorkControlTarget,
    storeIsCurrent: () -> Boolean,
  ): WorkControlResult {
    val operation: (UnitOfWork) -> WorkControlResult = {
      val rejection = validate(target, WorkControlAction.PAUSE, storeIsCurrent)
      rejection ?: when (controls.pause(target.issueKey, target.repositoryRoot, target.workflowId).status) {
        GoalRunnerPauseStatus.NOT_FOUND -> WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
        else -> WorkControlResult.IntentRecorded
      }
    }
    val observation = target.databaseFileObservation ?: return database.transaction(operation)
    return when (val result = database.transactionFenced(observation, operation)) {
      FencedTransactionResult.ChangedTarget -> WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      is FencedTransactionResult.Committed -> result.value
    }
  }

  private fun validate(
    target: WorkControlTarget,
    action: WorkControlAction,
    storeIsCurrent: () -> Boolean,
  ): WorkControlResult.Rejected? =
    database.read { unit ->
      val observation = unit.workList.observation()
      if (!storeIsCurrent() || target.observation != null && target.observation != observation) {
        return@read WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
      }
      if (unit.workList.findByWorkflowId(target.workflowId).isEmpty()) {
        return@read WorkControlResult.Rejected(WorkControlRejection.UNKNOWN_TARGET)
      }
      val item =
        status.projectBoardSelection(listOf(target.workflowId), WorkBoardRequest()).items.singleOrNull()
          ?: return@read WorkControlResult.Rejected(WorkControlRejection.UNKNOWN_TARGET)
      val snapshot = item.snapshot
      val lease = unit.goalRunnerControls.controlState(target.workflowId).executionLease
      val actual =
        WorkControlTarget(
          snapshot.workflowId.orEmpty(), snapshot.repositoryIdentity,
          Path.of(item.repositoryRoot).normalize(), snapshot.issueKey.orEmpty(), snapshot.execution?.executionId,
          snapshot.execution?.statusRevision, snapshot.execution?.statusStoreId, snapshot.execution?.runSequence,
          snapshot.updatedAt, lease?.ownerToken, lease?.generation,
          observation.takeIf { target.observation != null },
          item.databaseFileObservation,
        )
      when {
        actual != target.copy(repositoryRoot = target.repositoryRoot.normalize()) ->
          WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
        !(if (action == WorkControlAction.PAUSE) workPauseEligible(snapshot) else workControlEligible(snapshot)) ->
          WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
        else -> null
      }
    }
}
