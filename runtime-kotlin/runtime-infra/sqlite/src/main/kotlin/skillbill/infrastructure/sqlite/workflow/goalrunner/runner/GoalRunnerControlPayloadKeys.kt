package skillbill.infrastructure.sqlite.workflow.goalrunner.runner

internal object GoalRunnerControlPayloadKeys {
  const val BLOCKED_REASON = "goal_blocked_reason"
  const val QA_SELECTION = "qa_selection"
  const val EXECUTION_WORKTREE_PATH = "execution_worktree_path"
  const val EXECUTION_WORKTREE_REMOVED_AT = "execution_worktree_removed_at"
  const val EXECUTION_WORKTREE_RETENTION_REASON = "execution_worktree_retention_reason"
  const val EXECUTION_WORKTREE_RETENTION_DETAIL = "execution_worktree_retention_detail"
  const val EXECUTION_MAIN_CHECKOUT_OUTCOME = "execution_main_checkout_outcome"
  const val EXECUTION_MAIN_CHECKOUT_DETAIL = "execution_main_checkout_detail"
  const val LAUNCH_WARNING = "launch_warning"
  const val ARCHIVE_IMPORT_FINGERPRINT = "archive_import_fingerprint"
  const val ARCHIVE_IMPORT_SPECS_PENDING = "archive_import_specs_pending"
}
