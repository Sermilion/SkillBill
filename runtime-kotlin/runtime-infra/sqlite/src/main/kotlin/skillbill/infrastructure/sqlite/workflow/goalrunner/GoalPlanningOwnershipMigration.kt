package skillbill.infrastructure.sqlite.workflow.goalrunner

import skillbill.ports.goalrunner.GOAL_PLANNING_OWNERSHIP_CONTRACT_VERSION
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import java.sql.Connection

internal fun addGoalPlanningOwnership(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS goal_planning_ownership (
        reservation_id TEXT PRIMARY KEY,
        contract_version TEXT NOT NULL CHECK (contract_version = '$GOAL_PLANNING_OWNERSHIP_CONTRACT_VERSION'),
        repository_identity TEXT NOT NULL,
        normalized_issue_key TEXT NOT NULL,
        workflow_id TEXT NOT NULL UNIQUE,
        owned_path TEXT NOT NULL,
        initial_commit TEXT NOT NULL,
        ownership_state TEXT NOT NULL CHECK (ownership_state IN (
          '${GoalPlanningOwnership.State.RESERVED.wireValue}',
          '${GoalPlanningOwnership.State.INITIALIZED.wireValue}',
          '${GoalPlanningOwnership.State.TRANSFERRED.wireValue}',
          '${GoalPlanningOwnership.State.SUPERSEDED.wireValue}')),
        parent_workflow_id TEXT,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
        CHECK ((ownership_state = '${GoalPlanningOwnership.State.TRANSFERRED.wireValue}') =
          (parent_workflow_id IS NOT NULL))
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE UNIQUE INDEX IF NOT EXISTS idx_goal_planning_ownership_active_issue
        ON goal_planning_ownership(repository_identity, normalized_issue_key)
        WHERE ownership_state NOT IN (
          '${GoalPlanningOwnership.State.TRANSFERRED.wireValue}',
          '${GoalPlanningOwnership.State.SUPERSEDED.wireValue}')
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE UNIQUE INDEX IF NOT EXISTS idx_goal_planning_ownership_active_path
        ON goal_planning_ownership(owned_path)
        WHERE ownership_state NOT IN (
          '${GoalPlanningOwnership.State.TRANSFERRED.wireValue}',
          '${GoalPlanningOwnership.State.SUPERSEDED.wireValue}')
      """.trimIndent(),
    )
  }
}

internal fun recordGoalPlanningGitWorktreeIdentity(connection: Connection) {
  val exists =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT 1 FROM pragma_table_info('goal_planning_ownership') WHERE name = 'git_worktree_identity'",
      ).use { it.next() }
    }
  if (!exists) {
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE goal_planning_ownership ADD COLUMN git_worktree_identity TEXT")
    }
  }
}
