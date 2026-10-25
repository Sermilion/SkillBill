package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.infrastructure.sqlite.core.migration.DatabaseColumnMigrations
import java.sql.Connection

internal object GoalTelemetryMigration {
  fun apply(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(CREATE_GOAL_RUN_SESSIONS_SQL)
    }
    connection.createStatement().use { statement ->
      statement.execute(CREATE_GOAL_SUBTASK_EVENTS_SQL)
    }
    connection.createStatement().use { statement ->
      statement.execute(CREATE_GOAL_ISSUE_PROGRESS_SQL)
    }
  }

  private const val CREATE_GOAL_RUN_SESSIONS_SQL: String =
    """
    CREATE TABLE IF NOT EXISTS goal_run_sessions (
      workflow_id TEXT PRIMARY KEY,
      issue_key TEXT NOT NULL,
      feature_name TEXT NOT NULL DEFAULT '',
      subtask_total INTEGER NOT NULL DEFAULT 0,
      resumed INTEGER NOT NULL DEFAULT 0,
      started_at TEXT NOT NULL DEFAULT '',
      started_event_emitted_at TEXT,
      status TEXT,
      finished_at TEXT,
      finished_duration_ms INTEGER,
      subtasks_complete INTEGER,
      subtasks_blocked INTEGER,
      subtasks_skipped INTEGER,
      finished_event_emitted_at TEXT
    )
    """

  private const val CREATE_GOAL_SUBTASK_EVENTS_SQL: String =
    """
    CREATE TABLE IF NOT EXISTS goal_subtask_events (
      issue_key TEXT NOT NULL,
      workflow_id TEXT NOT NULL,
      subtask_id INTEGER NOT NULL,
      subtask_name TEXT NOT NULL DEFAULT '',
      status TEXT NOT NULL,
      started_at TEXT NOT NULL,
      finished_at TEXT NOT NULL,
      duration_ms INTEGER NOT NULL,
      attempt_count INTEGER NOT NULL,
      blocked_reason TEXT,
      finalizing_agent_id TEXT,
      participating_agent_ids TEXT NOT NULL DEFAULT '[]',
      subtask_event_emitted_at TEXT,
      PRIMARY KEY (issue_key, subtask_id, workflow_id)
    )
    """

  private const val CREATE_GOAL_ISSUE_PROGRESS_SQL: String =
    """
    CREATE TABLE IF NOT EXISTS goal_issue_progress (
      parent_workflow_id TEXT NOT NULL,
      issue_key TEXT NOT NULL,
      total_invocations INTEGER NOT NULL DEFAULT 0,
      total_blocks INTEGER NOT NULL DEFAULT 0,
      total_resumes INTEGER NOT NULL DEFAULT 0,
      first_started_at TEXT,
      finished_at TEXT,
      status TEXT,
      subtasks_complete INTEGER,
      subtasks_blocked INTEGER,
      subtasks_skipped INTEGER,
      mode TEXT NOT NULL DEFAULT 'runtime',
      finished_event_emitted_at TEXT,
      PRIMARY KEY (parent_workflow_id, issue_key)
    )
    """
}

private const val COUNT_COLUMN_DEFINITION = "INTEGER NOT NULL DEFAULT 0"

private val GOAL_SUBTASK_EVENT_COUNT_COLUMNS =
  listOf(
    "interaction_scenarios_traced",
    "interaction_scenarios_broken",
    "interaction_scenarios_repaired",
    "interaction_scenarios_unknown",
    "test_probes_killed",
    "test_probes_survived",
    "test_probes_repaired",
    "test_probes_not_run",
  )

private val GOAL_RUN_SESSION_COUNT_COLUMNS =
  listOf(
    "goal_assessment_blocker_findings",
    "goal_assessment_major_findings",
    "goal_assessment_minor_findings",
    "goal_assessment_confirmed",
    "goal_assessment_refuted",
    "goal_assessment_rounds",
    "goal_assessment_open_findings",
  )

private const val GOAL_RUN_SESSION_EXIT_REASON_COLUMN = "goal_assessment_exit_reason"

internal fun recordGoalSubtaskTraceProbeCounts(connection: Connection) {
  if (!DatabaseColumnMigrations.tableExists(connection, "goal_subtask_events")) return
  GOAL_SUBTASK_EVENT_COUNT_COLUMNS.forEach { column ->
    DatabaseColumnMigrations.ensureColumn(connection, "goal_subtask_events", column, COUNT_COLUMN_DEFINITION)
  }
}

internal fun recordGoalAssessmentCounts(connection: Connection) {
  if (!DatabaseColumnMigrations.tableExists(connection, "goal_run_sessions")) return
  GOAL_RUN_SESSION_COUNT_COLUMNS.forEach { column ->
    DatabaseColumnMigrations.ensureColumn(connection, "goal_run_sessions", column, COUNT_COLUMN_DEFINITION)
  }
  DatabaseColumnMigrations.ensureColumn(connection, "goal_run_sessions", GOAL_RUN_SESSION_EXIT_REASON_COLUMN, "TEXT")
}
