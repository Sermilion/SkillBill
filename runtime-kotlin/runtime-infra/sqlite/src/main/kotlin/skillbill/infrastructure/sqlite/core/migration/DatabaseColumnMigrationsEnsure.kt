package skillbill.infrastructure.sqlite.core.migration

import skillbill.infrastructure.sqlite.core.schema.FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION
import java.sql.Connection

internal object DatabaseColumnMigrationsEnsure {
  private val safeIdentifierPattern = Regex("^[a-z_][a-z0-9_]*$")

  fun ensureQualityCheckSessionColumns(connection: Connection) {
    ensureColumn(connection, "quality_check_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillBlankColumn(connection, "quality_check_sessions", "started_at", "CURRENT_TIMESTAMP")
    ensureColumn(connection, "quality_check_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "finished_event_emitted_at", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "routed_skill", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "detected_stack", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "fallback", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "quality_check_sessions", "fallback_reason", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "scope_type", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "initial_failure_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "quality_check_sessions", "final_failure_count", "INTEGER")
    ensureColumn(connection, "quality_check_sessions", "iterations", "INTEGER")
    ensureColumn(connection, "quality_check_sessions", "result", "TEXT")
    ensureColumn(connection, "quality_check_sessions", "failing_check_names", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "quality_check_sessions", "unsupported_reason", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(
      connection = connection,
      tableName = "quality_check_sessions",
      columnName = "duplicate_terminal_finished_events",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
    ensureStaleReasonColumn(connection, "quality_check_sessions")
  }

  private fun ensureStaleReasonColumn(
    connection: Connection,
    tableName: String,
  ) {
    ensureColumn(connection, tableName, "stale_reason", "TEXT")
  }

  fun ensureFeatureTaskRuntimeSessionColumns(connection: Connection) {
    ensureFeatureTaskRuntimeSessionLifecycleColumns(connection)
    ensureFeatureTaskRuntimeSessionMetricColumns(connection)
    ensureFeatureTaskRuntimeSessionCorrelationColumns(connection)
    ensureStaleReasonColumn(connection, "feature_task_runtime_sessions")
  }

  fun ensureFeatureVerifyWorkflowColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_workflows",
      columnName = "workflow_name",
      definition = "TEXT NOT NULL DEFAULT 'bill-feature-verify'",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_workflows",
      columnName = "contract_version",
      definition = "TEXT NOT NULL DEFAULT '$FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION'",
    )
  }

  internal fun ensureReviewRunColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "review_session_id",
      definition = "TEXT",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "review_finished_at",
      definition = "TEXT",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "review_finished_event_emitted_at",
      definition = "TEXT",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "specialist_reviews",
      definition = "TEXT NOT NULL DEFAULT ''",
    )
    ensureColumn(
      connection = connection,
      tableName = "review_runs",
      columnName = "orchestrated_run",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )

    ensureColumn(connection, "review_runs", "execution_mode", "TEXT")
    ensureColumn(connection, "review_runs", "routed_skill_canonical", "TEXT NOT NULL DEFAULT 'unresolved'")
    ensureColumn(connection, "review_runs", "detected_stack_canonical", "TEXT NOT NULL DEFAULT 'unresolved'")
    ensureColumn(connection, "review_runs", "detected_scope_canonical", "TEXT NOT NULL DEFAULT 'unresolved'")
    ensureColumn(connection, "review_runs", "detected_scope_detail", "TEXT")

    ensureColumn(connection, "review_runs", "integration_terminal_outcome", "TEXT")
    ensureColumn(connection, "review_runs", "integration_commit_sequence_digest", "TEXT")

    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_review_runs_routed_skill_canonical " +
          "ON review_runs(routed_skill_canonical, review_run_id)",
      )
    }
  }

  fun ensureFeatureImplementSessionColumns(connection: Connection) {
    ensureColumn(connection, "feature_implement_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillFeatureImplementStartedAt(connection)
    ensureColumn(connection, "feature_implement_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "finished_event_emitted_at", "TEXT")
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "source",
      definition = "TEXT NOT NULL DEFAULT 'production'",
    )
    ensureColumn(connection, "feature_implement_sessions", "issue_key_provided", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "issue_key_type", "TEXT NOT NULL DEFAULT 'none'")
    ensureColumn(connection, "feature_implement_sessions", "spec_input_types", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "spec_word_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "feature_size", "TEXT NOT NULL DEFAULT 'SMALL'")
    ensureColumn(connection, "feature_implement_sessions", "feature_name", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "rollout_needed", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "acceptance_criteria_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "open_questions_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_implement_sessions", "spec_summary", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "completion_status", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_implement_sessions", "plan_correction_count", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "plan_task_count", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "plan_phase_count", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "feature_flag_used", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "feature_flag_pattern", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "files_created", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "files_modified", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "tasks_completed", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "review_iterations", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "audit_result", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "audit_iterations", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "validation_result", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "boundary_history_written", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "pr_created", "INTEGER")
    ensureColumn(connection, "feature_implement_sessions", "plan_deviation_notes", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "boundary_history_value",
      definition = "TEXT NOT NULL DEFAULT 'none'",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "child_steps_json",
      definition = "TEXT NOT NULL DEFAULT ''",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_implement_sessions",
      columnName = "duplicate_terminal_finished_events",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
    ensureColumn(connection, "feature_implement_sessions", "estimated_phase_tokens_json", "TEXT")
    ensureColumn(connection, "feature_implement_sessions", "estimated_total_tokens", "INTEGER")
  }

  fun ensureFeatureVerifySessionColumns(connection: Connection) {
    ensureColumn(connection, "feature_verify_sessions", "started_at", "TEXT NOT NULL DEFAULT ''")
    backfillBlankColumn(connection, "feature_verify_sessions", "started_at", "CURRENT_TIMESTAMP")
    ensureColumn(connection, "feature_verify_sessions", "started_event_emitted_at", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "finished_at", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "finished_event_emitted_at", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "acceptance_criteria_count", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_verify_sessions", "rollout_relevant", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "feature_verify_sessions", "spec_summary", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_verify_sessions", "feature_flag_audit_performed", "INTEGER")
    ensureColumn(connection, "feature_verify_sessions", "review_iterations", "INTEGER")
    ensureColumn(connection, "feature_verify_sessions", "audit_result", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "completion_status", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "gaps_found", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "feature_verify_sessions", "qa_outcome", "TEXT")
    ensureColumn(connection, "feature_verify_sessions", "qa_reason", "TEXT")
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_sessions",
      columnName = "history_relevance",
      definition = "TEXT NOT NULL DEFAULT 'none'",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_sessions",
      columnName = "history_helpfulness",
      definition = "TEXT NOT NULL DEFAULT 'none'",
    )
    ensureColumn(
      connection = connection,
      tableName = "feature_verify_sessions",
      columnName = "duplicate_terminal_finished_events",
      definition = "INTEGER NOT NULL DEFAULT 0",
    )
    ensureStaleReasonColumn(connection, "feature_verify_sessions")
  }

  fun backfillFeatureImplementStartedAt(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE feature_implement_sessions
        SET started_at = COALESCE(
          (
            SELECT feature_task_workflows.started_at
            FROM feature_task_workflows
            WHERE feature_task_workflows.session_id = feature_implement_sessions.session_id
              AND feature_task_workflows.started_at IS NOT NULL
              AND feature_task_workflows.started_at != ''
            ORDER BY feature_task_workflows.started_at
            LIMIT 1
          ),
          CURRENT_TIMESTAMP
        )
        WHERE started_at IS NULL OR started_at = ''
        """.trimIndent(),
      )
    }
  }

  internal fun ensureColumn(
    connection: Connection,
    tableName: String,
    columnName: String,
    definition: String,
  ): Boolean {
    require(tableName.matches(safeIdentifierPattern)) { "Unsafe table name: '$tableName'" }
    require(columnName.matches(safeIdentifierPattern)) { "Unsafe column name: '$columnName'" }
    if (tableColumnNames(connection = connection, tableName = tableName).contains(columnName)) {
      return false
    }
    connection.createStatement().use { statement ->
      statement.execute("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
    }
    return true
  }

  fun backfillBlankColumn(
    connection: Connection,
    tableName: String,
    columnName: String,
    expression: String,
  ) {
    require(tableName.matches(safeIdentifierPattern)) { "Unsafe table name: '$tableName'" }
    require(columnName.matches(safeIdentifierPattern)) { "Unsafe column name: '$columnName'" }
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE $tableName
        SET $columnName = $expression
        WHERE $columnName IS NULL OR $columnName = ''
        """.trimIndent(),
      )
    }
  }

  fun tableColumnNames(
    connection: Connection,
    tableName: String,
  ): Set<String> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($tableName)").use { resultSet ->
        buildSet {
          while (resultSet.next()) {
            add(resultSet.getString("name"))
          }
        }
      }
    }
}
