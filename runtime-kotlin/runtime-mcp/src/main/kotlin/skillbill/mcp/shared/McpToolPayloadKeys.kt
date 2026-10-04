package skillbill.mcp.shared

internal object McpToolPayloadKeys {
  const val TOOL: String = "tool"
  const val CONTENT: String = "content"
  const val TEXT: String = "text"
  const val TYPE: String = "type"
  const val IS_ERROR: String = "isError"
  const val ENVELOPE: String = "envelope"
  const val PAYLOAD: String = "payload"
  const val ORCHESTRATED: String = "orchestrated"
  const val REPO: String = "repo"
  const val DRY_RUN: String = "dry_run"
  const val REPOSITORY_IDENTITY: String = "repository_identity"
  const val GOVERNED_SPEC_PATH: String = "governed_spec_path"
  const val ARTIFACTS_PATCH: String = "artifacts_patch"
  const val STEP_UPDATES: String = "step_updates"
  const val FEATURE_VERIFY_FINISHED: String = "feature_verify_finished"
  const val GENERATED_DESCRIPTION: String = "generated_description"
  const val FINAL_PR_BODY: String = "final_pr_body"
  const val QUALITY_CHECK_FINISHED: String = "quality_check_finished"
  const val ADD_LEARNING: String = "add_learning"
}
