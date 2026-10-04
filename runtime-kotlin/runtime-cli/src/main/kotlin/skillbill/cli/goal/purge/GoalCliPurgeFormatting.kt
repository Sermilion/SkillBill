package skillbill.cli.goal.purge

import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalRunnerPurgeResult

internal fun GoalRunnerPurgeResult.toGoalPurgeCliMap(): Map<String, Any?> {
  val status = if (refusalReason != null) "refused" else CliPayloadStatus.OK
  return linkedMapOf(
    SharedPayloadKeys.STATUS to status,
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    GoalRunnerPurgePayloadKeys.PARENT_WORKFLOW_ID to parentWorkflowId,
    GoalRunnerPurgePayloadKeys.DELETED_CHILD_WORKFLOW_IDS to deletedChildWorkflowIds,
    GoalRunnerPurgePayloadKeys.SPEC_RESTORED to specRestored,
    GoalRunnerPurgePayloadKeys.REFUSAL_REASON to refusalReason,
  )
}

internal fun goalPurgeText(result: GoalRunnerPurgeResult): String {
  val status = if (result.refusalReason == null) CliPayloadStatus.OK else "refused"
  return when (status) {
    CliPayloadStatus.OK -> "Purged goal runtime state for ${result.issueKey}."
    "refused" -> "Purge refused for ${result.issueKey}: ${result.refusalReason}"
    else -> "Goal purge for ${result.issueKey}: $status."
  }
}

internal fun goalPurgeExitCode(result: GoalRunnerPurgeResult): Int = if (result.refusalReason == null) 0 else 1
