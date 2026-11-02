package skillbill.contracts.decomposition

import skillbill.contracts.SharedPayloadKeys

object DecompositionManifestPayloadKeys {
  const val ISSUE_KEY: String = SharedPayloadKeys.ISSUE_KEY
  const val FEATURE_NAME: String = "feature_name"
  const val FEATURE_BRANCH: String = "feature_branch"
  const val CURRENT_SUBTASK_INTENT: String = "current_subtask_intent"
  const val COMMIT_SHA: String = "commit_sha"
  const val COMMIT_PUSH_RESULT: String = "commit_push_result"
  const val PRE_COMMIT_PROJECTION: String = "pre_commit_projection"
  const val BLOCKED_REASON: String = "blocked_reason"
  const val LAST_RESUMABLE_STEP: String = "last_resumable_step"
  const val FINALIZING_AGENT_ID: String = "finalizing_agent_id"
  const val PARTICIPATING_AGENT_IDS: String = "participating_agent_ids"
  const val ACTION: String = "action"
  const val DECOMPOSITION_STATUS: String = "decomposition_status"
  const val DECOMPOSITION_SUBTASK_ID: String = "decomposition_subtask_id"
  const val DECOMPOSITION_SUBTASK_SPEC_PATH: String = "decomposition_subtask_spec_path"
}
