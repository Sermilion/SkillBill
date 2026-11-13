package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.area.WorkBoardReadModelRebuildMigration
import skillbill.infrastructure.sqlite.core.schema.BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN
import skillbill.infrastructure.sqlite.core.schema.BRANCH_WORKTREE_RETAINED_PATH_COLUMN
import skillbill.infrastructure.sqlite.core.schema.OPERATOR_FINALIZED_AT_COLUMN
import skillbill.infrastructure.sqlite.core.schema.STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES_SQL
import skillbill.infrastructure.sqlite.core.schema.reinstallFeatureVerifyWorkflowChangeTriggers
import skillbill.infrastructure.sqlite.core.schema.reinstallStandalonePhaseStatusChangeTriggers
import skillbill.infrastructure.sqlite.workflow.goalrunner.addGoalPlanningCheckoutRecovery
import skillbill.workflow.model.WorkflowStatus
import java.sql.Connection

internal val operatorFinalizedAtMigrations: List<DatabaseMigration> =
  listOf(
    DatabaseMigration(
      version = 126,
      name = "fence-goal-planning-checkout-recovery",
      operation = ::addGoalPlanningCheckoutRecovery,
    ),
    DatabaseMigration(
      version = 127,
      name = "add-operator-finalized-at",
      operation = ::addOperatorFinalizedAt,
    ),
  )

internal fun addOperatorFinalizedAt(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(connection, "standalone_phase_status", OPERATOR_FINALIZED_AT_COLUMN, "TEXT")
  DatabaseColumnMigrations.ensureColumn(connection, "feature_verify_workflows", OPERATOR_FINALIZED_AT_COLUMN, "TEXT")
  val completed = WorkflowStatus.COMPLETED.wireValue
  connection.createStatement().use { statement ->
    statement.execute(
      """
      UPDATE standalone_phase_status
      SET $OPERATOR_FINALIZED_AT_COLUMN = finished_at
      WHERE finished_at IS NOT NULL
        AND lifecycle_state IN ($STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES_SQL)
        AND $OPERATOR_FINALIZED_AT_COLUMN IS NULL
      """.trimIndent(),
    )
    statement.execute(
      """
      UPDATE feature_verify_workflows
      SET $OPERATOR_FINALIZED_AT_COLUMN = finished_at
      WHERE finished_at IS NOT NULL
        AND workflow_status = '$completed'
        AND $OPERATOR_FINALIZED_AT_COLUMN IS NULL
      """.trimIndent(),
    )
  }
  reinstallStandalonePhaseStatusChangeTriggers(connection)
  reinstallFeatureVerifyWorkflowChangeTriggers(connection)
  WorkBoardReadModelRebuildMigration.apply(connection)
}

internal val branchWorktreeRetainedMigrations: List<DatabaseMigration> =
  listOf(
    DatabaseMigration(
      version = 128,
      name = "add-branch-worktree-retained",
      operation = ::addBranchWorktreeRetained,
    ),
    DatabaseMigration(
      version = 129,
      name = "add-feature-verify-branch-worktree-retained",
      operation = ::addFeatureVerifyBranchWorktreeRetained,
    ),
  )

internal fun addBranchWorktreeRetained(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    BRANCH_WORKTREE_RETAINED_PATH_COLUMN,
    "TEXT",
  )
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "standalone_phase_status",
    BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN,
    "TEXT",
  )
  reinstallStandalonePhaseStatusChangeTriggers(connection)
  reinstallFeatureVerifyWorkflowChangeTriggers(connection)
  WorkBoardReadModelRebuildMigration.apply(connection)
}

internal fun addFeatureVerifyBranchWorktreeRetained(connection: Connection) {
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "feature_verify_workflows",
    BRANCH_WORKTREE_RETAINED_PATH_COLUMN,
    "TEXT",
  )
  DatabaseColumnMigrations.ensureColumn(
    connection,
    "feature_verify_workflows",
    BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN,
    "TEXT",
  )
  reinstallStandalonePhaseStatusChangeTriggers(connection)
  reinstallFeatureVerifyWorkflowChangeTriggers(connection)
  WorkBoardReadModelRebuildMigration.apply(connection)
}
