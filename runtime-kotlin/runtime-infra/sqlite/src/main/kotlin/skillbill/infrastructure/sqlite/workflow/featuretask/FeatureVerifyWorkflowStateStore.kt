package skillbill.infrastructure.sqlite.workflow.featuretask

import skillbill.contracts.workflow.session.WorkflowContinueSessionSummary
import skillbill.error.shellcontent.terminalWorkflowWriteRefusedError
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.infrastructure.sqlite.core.schema.BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN
import skillbill.infrastructure.sqlite.core.schema.BRANCH_WORKTREE_RETAINED_PATH_COLUMN
import skillbill.infrastructure.sqlite.core.schema.FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION
import skillbill.infrastructure.sqlite.core.schema.OPERATOR_FINALIZED_AT_COLUMN
import skillbill.infrastructure.sqlite.workflow.WorkflowRowUpsertRequest
import skillbill.infrastructure.sqlite.workflow.getWorkflowRow
import skillbill.infrastructure.sqlite.workflow.getWorkflowRows
import skillbill.infrastructure.sqlite.workflow.listWorkflowRows
import skillbill.infrastructure.sqlite.workflow.upsertWorkflowRow
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.isTerminal
import java.sql.Connection
import java.time.Clock
import java.time.Instant

internal class FeatureVerifyWorkflowStateStore(
  private val connection: Connection,
  private val clock: Clock,
  private val workflowSnapshotValidator: WorkflowSnapshotValidator,
) {
  fun saveWorkflow(
    row: WorkflowStateRecord,
    acceptedSnapshotMutation: Boolean = false,
  ) {
    val current = connection.getWorkflowRow("feature_verify_workflows", row.workflowId)
    if (
      !acceptedSnapshotMutation &&
      current != null &&
      WorkflowStatus.fromWire(current.workflowStatus)?.isTerminal == true
    ) {
      if (current.sameTerminalSnapshot(row)) return
      throw terminalWorkflowWriteRefusedError(row.workflowId)
    }
    connection.upsertWorkflowRow(
      tableName = "feature_verify_workflows",
      row = row,
      request =
        WorkflowRowUpsertRequest(
          defaultContractVersion = FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION,
          clock = clock,
          workflowSnapshotValidator = workflowSnapshotValidator,
          acceptedSnapshotMutation = acceptedSnapshotMutation,
        ),
    )
  }

  fun getWorkflow(workflowId: String): WorkflowStateRecord? =
    connection.getWorkflowRow("feature_verify_workflows", workflowId)

  fun getWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    connection.getWorkflowRows("feature_verify_workflows", workflowIds)

  fun listWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listWorkflowRows("feature_verify_workflows", limit)

  fun finalizeOperatorCompletion(
    workflowId: String,
    operatorFinalizedAt: Instant,
    branchWorktreeRetainedPath: String? = null,
    branchWorktreeRetainedDetail: String? = null,
  ): Boolean {
    val completed = WorkflowStatus.COMPLETED.wireValue
    val updated =
      connection.prepareStatement(
        """
        UPDATE feature_verify_workflows
        SET $OPERATOR_FINALIZED_AT_COLUMN = ?,
          $BRANCH_WORKTREE_RETAINED_PATH_COLUMN = COALESCE(?, $BRANCH_WORKTREE_RETAINED_PATH_COLUMN),
          $BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN = COALESCE(?, $BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN)
        WHERE workflow_id = ?
          AND finished_at IS NOT NULL
          AND workflow_status = ?
          AND $OPERATOR_FINALIZED_AT_COLUMN IS NULL
        """.trimIndent(),
      ).use { statement ->
        statement.bindAll(
          operatorFinalizedAt.toString(),
          branchWorktreeRetainedPath,
          branchWorktreeRetainedDetail,
          workflowId,
          completed,
        )
        statement.executeUpdate()
      }
    return updated == 1
  }

  fun getSessionSummary(sessionId: String): WorkflowContinueSessionSummary? =
    connection.prepareStatement(
      """
      SELECT
        session_id,
        acceptance_criteria_count,
        rollout_relevant,
        spec_summary
      FROM feature_verify_sessions
      WHERE session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(sessionId)
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        WorkflowContinueSessionSummary(
          sessionId = resultSet.getString("session_id"),
          acceptanceCriteriaCount = resultSet.getInt("acceptance_criteria_count"),
          rolloutRelevant = resultSet.getInt("rollout_relevant") == 1,
          specSummary = resultSet.getString("spec_summary"),
        )
      }
    }
}

private fun WorkflowStateRecord.sameTerminalSnapshot(other: WorkflowStateRecord): Boolean =
  workflowStatus == other.workflowStatus && currentStepId == other.currentStepId &&
    stepsJson == other.stepsJson && artifactsJson == other.artifactsJson && finishedAt == other.finishedAt
