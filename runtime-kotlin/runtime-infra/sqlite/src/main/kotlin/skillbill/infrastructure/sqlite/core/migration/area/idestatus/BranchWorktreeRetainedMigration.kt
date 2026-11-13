package skillbill.infrastructure.sqlite.core.migration.area.idestatus

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import skillbill.infrastructure.sqlite.core.migration.DatabaseMigration
import skillbill.infrastructure.sqlite.core.migration.area.WorkBoardReadModelRebuildMigration
import skillbill.infrastructure.sqlite.core.schema.BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN
import skillbill.infrastructure.sqlite.core.schema.BRANCH_WORKTREE_RETAINED_PATH_COLUMN
import skillbill.infrastructure.sqlite.core.schema.reinstallFeatureVerifyWorkflowChangeTriggers
import skillbill.infrastructure.sqlite.core.schema.reinstallStandalonePhaseStatusChangeTriggers
import java.sql.Connection

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
