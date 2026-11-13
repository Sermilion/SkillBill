package skillbill.infrastructure.sqlite.workflow.goalrunner

import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
import java.sql.Connection

internal fun addGoalPlanningCheckoutRecovery(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS goal_planning_checkout_recovery (
        generation TEXT PRIMARY KEY CHECK (length(generation) > 0),
        reservation_id TEXT NOT NULL REFERENCES goal_planning_ownership(reservation_id),
        previous_git_identity TEXT NOT NULL,
        repository_identity TEXT NOT NULL,
        normalized_issue_key TEXT NOT NULL,
        workflow_id TEXT NOT NULL,
        owned_path TEXT NOT NULL,
        recoverable_commit TEXT NOT NULL,
        ownership_state TEXT NOT NULL,
        parent_workflow_id TEXT,
        recovery_state TEXT NOT NULL CHECK (recovery_state IN (
          '${GoalPlanningCheckoutRecovery.State.FENCED.wireValue}',
          '${GoalPlanningCheckoutRecovery.State.PROVEN.wireValue}',
          '${GoalPlanningCheckoutRecovery.State.CONSUMED.wireValue}')),
        replacement_git_identity TEXT,
        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE UNIQUE INDEX IF NOT EXISTS idx_planning_checkout_recovery_active
      ON goal_planning_checkout_recovery(reservation_id)
      WHERE recovery_state <> '${GoalPlanningCheckoutRecovery.State.CONSUMED.wireValue}'
      """.trimIndent(),
    )
  }
}
