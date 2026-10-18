package skillbill.contracts.workflow.identity.task

object FeatureTaskRuntimeGoalContinuationLaunchTokens {
  const val FEATURE_TASK_COMMAND: String = "feature-task"
  const val RESUME_SUBCOMMAND: String = "resume"

  const val GOAL_CONTINUATION_ENV: String = "SKILL_BILL_GOAL_CONTINUATION"
  const val GOAL_PARENT_ISSUE_KEY_ENV: String = "SKILL_BILL_GOAL_PARENT_ISSUE_KEY"
  const val GOAL_SUBTASK_ID_ENV: String = "SKILL_BILL_GOAL_SUBTASK_ID"
  const val GOAL_BRANCH_ENV: String = "SKILL_BILL_GOAL_BRANCH"
  const val SUPPRESS_PR_ENV: String = "SKILL_BILL_SUPPRESS_PR"
  const val GOAL_PARENT_WORKFLOW_ID_ENV: String = "SKILL_BILL_GOAL_PARENT_WORKFLOW_ID"
  const val GOAL_LAST_RESUMABLE_STEP_ENV: String = "SKILL_BILL_GOAL_LAST_RESUMABLE_STEP"
  const val CODE_REVIEW_MODE_ENV: String = "SKILL_BILL_CODE_REVIEW_MODE"
  const val VALIDATION_DEPTH_ENV: String = "SKILL_BILL_VALIDATION_DEPTH"
  const val QUALITY_GATE_SELECTION_ENV: String = "SKILL_BILL_QUALITY_GATE_SELECTION"

  const val GOAL_PARENT_ISSUE_KEY_FLAG: String = "--goal-parent-issue-key"
  const val GOAL_SUBTASK_ID_FLAG: String = "--goal-subtask-id"
  const val GOAL_BRANCH_FLAG: String = "--goal-branch"
  const val GOAL_PARENT_WORKFLOW_ID_FLAG: String = "--goal-parent-workflow-id"
  const val GOAL_LAST_RESUMABLE_STEP_FLAG: String = "--goal-last-resumable-step"
  const val GOAL_REVIEW_BASE_SHA_FLAG: String = "--goal-review-base-sha"
  const val GOAL_BASELINE_UNTRACKED_PATH_FLAG: String = "--goal-baseline-untracked-path"
  const val CODE_REVIEW_MODE_FLAG: String = "--code-review-mode"
  const val SUPPRESS_PR_FLAG: String = "--suppress-pr"
  const val QUALITY_GATE_SELECTION_FLAG: String = "--quality-gate-selection"
  const val WORKFLOW_ID_FLAG: String = "--workflow-id"
  const val AGENT_ADDON_SELECTION_JSON_FLAG: String = "--agent-addon-selection-json"
}
