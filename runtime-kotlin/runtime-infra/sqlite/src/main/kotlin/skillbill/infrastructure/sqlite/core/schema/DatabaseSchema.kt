package skillbill.infrastructure.sqlite.core.schema

import java.sql.Connection

internal const val FEATURE_VERIFY_WORKFLOW_CONTRACT_VERSION: String = "0.3"

internal object DatabaseSchema {
  val tableNames: Set<String> =
    setOf(
      "schema_migrations",
      "review_runs",
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
      "idx_learnings_scope",
      "idx_telemetry_outbox_pending",
      "idx_feature_task_workflows_updated",
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
