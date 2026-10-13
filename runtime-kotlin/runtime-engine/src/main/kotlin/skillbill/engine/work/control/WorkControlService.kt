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
) {
  fun request(
    target: WorkControlTarget,
    action: WorkControlAction,
    storeIsCurrent: () -> Boolean = { true },
  ): WorkControlResult {
    if (action == WorkControlAction.STOP) {
      var rejection: WorkControlResult.Rejected? = null
      val result =
        controls.stop(
          target.issueKey,
          target.repositoryRoot,
          target.workflowId,
          target.databaseFileObservation,
        ) {
          rejection = validate(target, action, storeIsCurrent)
          rejection == null
        }
      rejection?.let { return it }
      return when (result.status) {
        GoalRunnerStopStatus.NOT_FOUND -> WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
        GoalRunnerStopStatus.IDENTITY_MISMATCH -> WorkControlResult.Rejected(WorkControlRejection.OWNERSHIP_MISMATCH)
        GoalRunnerStopStatus.CHANGED_TARGET -> WorkControlResult.Rejected(WorkControlRejection.CHANGED_TARGET)
        GoalRunnerStopStatus.TERMINATION_INCOMPLETE ->
          WorkControlResult.Rejected(WorkControlRejection.TERMINATION_INCOMPLETE)
        else -> WorkControlResult.Accepted
      }
    }
    val operation: (UnitOfWork) -> WorkControlResult = {
      val rejection = validate(target, action, storeIsCurrent)
      rejection ?: when (controls.pause(target.issueKey, target.repositoryRoot, target.workflowId).status) {
        GoalRunnerPauseStatus.NOT_FOUND -> WorkControlResult.Rejected(WorkControlRejection.NOT_FOUND)
        else -> WorkControlResult.Accepted
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
        !workControlEligible(snapshot) || action == WorkControlAction.PAUSE && snapshot.pauseRequested == true ->
          WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET)
        else -> null
      }
    }
}
