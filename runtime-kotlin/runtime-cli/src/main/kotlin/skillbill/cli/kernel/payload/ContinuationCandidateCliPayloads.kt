package skillbill.cli.kernel.payload

import skillbill.application.continuation.model.GoalContinuationCandidate
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate

internal fun GoalContinuationCandidate.toGoalContinuationCliMap(): Map<String, Any?> =
  linkedMapOf(
    "parent_workflow_id" to parentWorkflowId,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    SharedPayloadKeys.STATUS to status,
    "current_subtask_id" to currentSubtaskId,
    "current_action" to currentAction,
    "complete_count" to completeCount,
    "pending_count" to pendingCount,
    "blocked_count" to blockedCount,
    "updated_at" to updatedAt,
    SharedPayloadKeys.SUMMARY to summary,
  )

internal fun FeatureTaskContinuationCandidate.toFeatureTaskContinuationCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    "mode" to mode.wireValue,
    SharedPayloadKeys.STATUS to status,
    "current_step" to currentStep,
    "governed_spec_path" to governedSpecPath,
    "updated_at" to updatedAt,
    "liveness" to
      liveness?.let {
        linkedMapOf(
          "classification" to it.classification,
          "last_evidence_at" to it.lastEvidenceAt,
          "evidence" to it.evidence,
        )
      },
    SharedPayloadKeys.SUMMARY to summary,
  )
