package skillbill.infrastructure.sqlite.core.schema

import skillbill.idestatus.model.IdeStatusDurableWorkflowState
import skillbill.idestatus.model.IdeStatusLifecycleState
import java.sql.Connection

internal const val FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION: String = "0.4"
internal const val OPERATOR_FINALIZED_AT_COLUMN: String = "operator_finalized_at"
internal const val BRANCH_WORKTREE_RETAINED_PATH_COLUMN: String = "branch_worktree_retained_path"
internal const val BRANCH_WORKTREE_RETAINED_DETAIL_COLUMN: String = "branch_worktree_retained_detail"

internal val STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES: Set<String> =
  setOf(
    IdeStatusLifecycleState.TERMINAL.wireValue,
    IdeStatusDurableWorkflowState.COMPLETED.wireValue,
    "success",
  )

internal val STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES_SQL: String =
  STANDALONE_SUCCESSFUL_COMPLETION_LIFECYCLES.joinToString(", ") { "'$it'" }

internal object DatabaseSchema {
  val tableNames: Set<String> =
    setOf(
      "schema_migrations",
      "review_runs",
      "review_finding_actions",
      "diff_comment_threads",
      "diff_comments",
      "review_run_lanes",
      "review_run_finding_lanes",
      "findings",
      "feedback_events",
      "learnings",
      "telemetry_outbox",
      "session_learnings",
      "quality_check_sessions",
      "feature_verify_sessions",
      "feature_implement_sessions",
      "feature_task_runtime_sessions",
      "feature_task_workflows",
      "feature_task_execution_identities",
      "feature_task_runtime_worker_leases",
      "feature_verify_workflows",
      "ide_status_execution_registry",
      "standalone_phase_status",
      "validation_check_events",
      AgentBuildRunsKeys.TABLE,
      "ide_status_workflow_execution",
      "goal_run_sessions",
      "goal_subtask_events",
      "goal_issue_progress",
      "goal_planning_preparations",
      "goal_shared_preplans",
      "goal_subtask_plans",
      "goal_runner_controls",
      "telemetry_reconciliation_state",
      "telemetry_local_secrets",
      "unaddressed_findings",
      "review_finding_outcomes",
      "review_run_finding_verdicts",
      "review_run_stage_boundaries",
      "review_run_pass_claims",
      "review_run_spec_projections",
      "rejected_output_diagnostics",
      "producer_output_evidence",
      "agent_activity_stamps",
      "worktree_edit_journal",
    )

  val indexNames: Set<String> =
    setOf(
      "idx_feedback_events_run",
      "review_finding_actions_run",
      "idx_diff_comment_threads_branch",
      "idx_diff_comment_threads_source_ref",
      "idx_diff_comments_thread_position",
      "idx_learnings_scope",
      "idx_telemetry_outbox_pending",
      "idx_feature_task_workflows_updated",
      "idx_feature_task_workflows_issue_key",
      "idx_feature_task_workflows_normalized_issue_key",
      "idx_feature_task_workflows_missing_issue_key",
      "idx_feature_task_workflows_parent_workflow_id",
      "idx_feature_task_identity_lookup",
      "idx_feature_implement_reconciliation_candidates",
      "idx_feature_task_runtime_reconciliation_candidates",
      "idx_feature_verify_reconciliation_candidates",
      "idx_quality_check_reconciliation_candidates",
      "idx_feature_task_workflows_reconciliation_activity",
      "idx_feature_verify_workflows_reconciliation_activity",
      "idx_goal_issue_reconciliation_candidates",
      "idx_goal_planning_preparations_lookup",
      "idx_goal_subtask_plans_ordered",
      "idx_telemetry_reconciliation_completed",
      "idx_unaddressed_findings_issue",
      "idx_unaddressed_findings_run",
      "idx_review_finding_outcomes_run",
      "idx_rejected_output_diagnostics_selector",
      "idx_review_run_lanes_pack_area",
      "idx_review_run_finding_verdicts_run",
      "idx_review_run_stage_boundaries_run",
      "idx_review_runs_routed_skill_canonical",
      "idx_findings_lane",
      "idx_worktree_edit_journal_workflow_recorded",
      "idx_standalone_phase_status_correlation",
      "idx_standalone_phase_status_retention",
      "idx_ide_status_workflow_execution_lookup",
      AgentBuildRunsKeys.RUN_INDEX,
      AgentBuildRunsKeys.PARENT_INDEX,
      AgentBuildRunsKeys.HISTORY_INDEX,
      "desktop_phase_board_group",
      "desktop_goal_issue_identity",
      "desktop_execution_identity_issue",
      "desktop_verify_issue_identity",
      "desktop_standalone_workflow_identity",
      "desktop_execution_invocation_identity",
    )

  fun createBaseSchema(connection: Connection) {
    (
      databaseSchemaStatements +
        DatabaseReviewLedgerSchema.reviewRunLaneStatements +
        DatabaseReviewLedgerSchema.reviewFindingOutcomeStatements +
        DatabaseReviewLedgerSchema.reviewStageStateStatements
    ).forEach { statementSql ->
      connection.createStatement().use { statement ->
        statement.execute(statementSql)
      }
    }
  }
}
