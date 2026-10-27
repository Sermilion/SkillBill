package skillbill.goalrunner.model.history

import skillbill.contracts.workflow.goal.GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION

enum class GoalChildHistoryDraftDisposition(val wireValue: String) {
  DRAFT("draft"),
  SKIP("skip"),
}

data class GoalChildHistoryDraft(
  val contractVersion: String = GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION,
  val parentWorkflowId: String,
  val subtaskId: Int,
  val childWorkflowId: String,
  val disposition: GoalChildHistoryDraftDisposition,
  val boundary: String? = null,
  val history: String? = null,
  val decisions: String? = null,
  val skipReason: String? = null,
) {
  fun validate() {
    require(contractVersion == GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION)
    require(parentWorkflowId.isNotBlank())
    require(subtaskId > 0)
    require(childWorkflowId.isNotBlank())
    when (disposition) {
      GoalChildHistoryDraftDisposition.DRAFT ->
        require(
          !boundary.isNullOrBlank() &&
            !history.isNullOrBlank() &&
            skipReason == null,
        )
      GoalChildHistoryDraftDisposition.SKIP ->
        require(
          !skipReason.isNullOrBlank() && boundary == null && history == null && decisions == null,
        )
    }
  }
}

sealed interface GoalChildHistoryDraftSaveResult {
  data class Saved(val record: GoalChildHistoryDraft) : GoalChildHistoryDraftSaveResult

  data object Conflict : GoalChildHistoryDraftSaveResult
}

object GoalChildHistoryDraftPayloadKeys {
  const val CONTRACT_VERSION = "contract_version"
  const val PARENT_WORKFLOW_ID = "parent_workflow_id"
  const val SUBTASK_ID = "subtask_id"
  const val CHILD_WORKFLOW_ID = "child_workflow_id"
  const val DISPOSITION = "disposition"
  const val BOUNDARY = "boundary"
  const val HISTORY = "history"
  const val DECISIONS = "decisions"
  const val SKIP_REASON = "skip_reason"
  const val OUTPUT_REASON = "reason"
}
