package skillbill.engine.goalrunner.model.history

internal object GoalFinalizationHistoryPayloadKeys {
  const val CONTRACT_VERSION = "contract_version"
  const val REPAIR_CONTEXT = "repair_context"
  const val OUTPUTS = "outputs"
  const val PHASE_ID = "phase_id"
  const val INVOCATION_ID = "invocation_id"
  const val OCCURRENCE = "occurrence"
  const val OUTPUT = "output"
  const val WARNING = "warning"
  const val COMPLETION = "completion"
  const val INPUT_FINGERPRINT = "input_fingerprint"
  const val PRE_WRITER_CONTENT_FINGERPRINT = "pre_writer_content_fingerprint"
  const val POST_WRITER_CONTENT_FINGERPRINT = "post_writer_content_fingerprint"
  const val LEGACY_PRE_WRITER_TREE_IDENTITY = "pre_writer_tree_identity"
  const val LEGACY_POST_WRITER_TREE_IDENTITY = "post_writer_tree_identity"
  const val ACCEPTED_OUTPUT = "accepted_output"
}
