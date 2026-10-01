package skillbill.infrastructure.sqlite.workflow.goalrunner.planning
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.infrastructure.sqlite.core.ops.inNestedWriteTransaction
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalPlanningPreparationStatus
import java.sql.Connection

internal class GoalPlanningPreparationRecordSql(
  private val connection: Connection,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun markPrepared(record: GoalPlanningPreparationRecord) {
    requirePreparedEnvelope(record)
    connection.inNestedWriteTransaction(diagnostics) {
      if (connection.upsertPreparedRow(record)) return@inNestedWriteTransaction
      val stored =
        connection.selectStoredRecoveryIdentity(record.parentGoalWorkflowId, record.subtaskId)
          ?: return@inNestedWriteTransaction
      val reason = recoveryIdentityFailure(stored, record) ?: return@inNestedWriteTransaction
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        workflowId = record.parentGoalWorkflowId,
        subtaskId = record.subtaskId,
        reason = reason,
      )
    }
  }

  fun findByGoalAndSubtask(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): GoalPlanningPreparationRecord? = connection.selectRecord(parentGoalWorkflowId, subtaskId)

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord> =
    connection.selectOrderedByGoal(parentGoalWorkflowId)

  fun preparedCount(parentGoalWorkflowId: String): Int = connection.countPrepared(parentGoalWorkflowId)

  fun firstMissingOrIncompleteSubtask(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
  ): Int? {
    if (orderedSubtaskIds.isEmpty()) return null
    val prepared = connection.preparedSubtaskStatuses(parentGoalWorkflowId)
    return orderedSubtaskIds.firstOrNull { id -> prepared[id] != GoalPlanningPreparationState.PREPARED.wireValue }
  }

  fun preparedStatus(
    parentGoalWorkflowId: String,
    subtaskId: Int,
  ): GoalPlanningPreparationStatus? = connection.selectStatus(parentGoalWorkflowId, subtaskId)

  fun deletePreparedByGoal(parentGoalWorkflowId: String): Int = connection.deletePreparedByGoal(parentGoalWorkflowId)
}
