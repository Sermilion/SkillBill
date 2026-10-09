package skillbill.cli.goal.purge

object GoalRunnerPurgePayloadKeys {
  const val CHECKPOINT_REFS_PRUNED: String = "checkpoint_refs_pruned"
  const val DELETED_CHILD_WORKFLOW_IDS: String = "deleted_child_workflow_ids"
  const val LEFTOVERS: String = "leftovers"
  const val NOTHING_TO_REMOVE: String = "nothing_to_remove"
  const val PARENT_WORKFLOW_IDS: String = "parent_workflow_ids"
  const val REFUSAL_REASON: String = "refusal_reason"
  const val REMOVED_PATHS: String = "removed_paths"
  const val REMOVED_ROW_COUNTS: String = "removed_row_counts"
  const val RETAINED_NOTE: String = "retained_note"
  const val SPEC_BUNDLE_ACTIONS: String = "spec_bundle_actions"
}
