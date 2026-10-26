package skillbill.engine.goalrunner.history

import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.goal.GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.goalrunner.model.GoalChildHistoryDraftPayloadKeys
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.workflow.model.WorkflowStepStatus

internal object GoalChildHistoryEvidenceKeys {
  const val EXECUTION_PLAN = "execution_plan"
  const val CONTINUATION = "continuation"
  const val HISTORY_STEP_STATUS = "history_step_status"
  const val HISTORY_RECORD = "history_record"
  const val ACCEPTANCE = "acceptance"
  const val RESTORE_ELIGIBLE = "restore_eligible"
  const val RESTORED = "restored"
}

internal data class GoalChildHistoryEvidence(
  val parentWorkflowId: String,
  val subtaskId: Int,
  val childWorkflowId: String,
  val executionPlan: Map<String, Any?>?,
  val continuation: Map<String, Any?>,
  val historyRecord: Map<String, Any?>?,
  val acceptance: GoalRunnerOutOfBandAcceptance?,
  val restoreEligible: Boolean,
  val restored: Boolean = false,
  val historyStepStatus: WorkflowStepStatus? = null,
) {
  fun toWire(): Map<String, Any?> {
    val identity = GoalChildHistoryDraftPayloadKeys
    val keys = GoalChildHistoryEvidenceKeys
    return mapOf(
      identity.CONTRACT_VERSION to GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION,
      identity.PARENT_WORKFLOW_ID to parentWorkflowId,
      identity.SUBTASK_ID to subtaskId,
      identity.CHILD_WORKFLOW_ID to childWorkflowId,
      keys.EXECUTION_PLAN to executionPlan,
      keys.CONTINUATION to continuation,
      keys.HISTORY_RECORD to historyRecord,
      keys.HISTORY_STEP_STATUS to historyStepStatus?.wireValue,
      keys.ACCEPTANCE to
        acceptance?.let {
          mapOf(
            DecompositionManifestPayloadKeys.COMMIT_SHA to it.commitSha,
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTANCE_REASON to it.reason,
            FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTED_AT to it.acceptedAt,
          )
        },
      keys.RESTORE_ELIGIBLE to restoreEligible,
      keys.RESTORED to restored,
    )
  }
}
