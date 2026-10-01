package skillbill.contracts.workflow.identity.task

object FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys {
  const val ISSUE_KEY: String = "issue_key"
  const val SUBTASK_ID: String = "subtask_id"
  const val SUPPRESS_PR: String = "suppress_pr"
  const val GOAL_BRANCH: String = "goal_branch"
  const val PARENT_WORKFLOW_ID: String = "parent_workflow_id"
  const val CODE_REVIEW_MODE: String = "code_review_mode"
  const val VALIDATION_DEPTH: String = "validation_depth"
  const val QUALITY_GATE_SELECTION: String = "quality_gate_selection"
  const val PARALLEL_REVIEW_AGENT: String = "parallel_review_agent"
  const val SUBTASK_NAME: String = "subtask_name"
  const val AGENT_ADDON_SELECTION: String = "agent_addon_selection"
  const val ADDON_SLUG: String = "slug"
  const val ADDON_SOURCE_IDENTITY: String = "source_identity"
  const val ADDON_CONTENT_SHA256: String = "content_sha256"
  const val ACCEPTANCE_REASON: String = "reason"
  const val ACCEPTED_AT: String = "accepted_at"
  const val ENTRIES: String = "entries"
}
