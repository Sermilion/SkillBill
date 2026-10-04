package skillbill.goalrunner

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.asGoalRunnerIntOrNull
import skillbill.workflow.model.goalobservability.asGoalWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

fun missingResultPrefixTerminalOutcomeArtifact(
  output: Any,
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
): FeatureTaskRuntimeWorkflowArtifactMap? {
  val wire = JsonCodec.anyToStringAnyMap(output) ?: return null
  return (JsonCodec.anyToStringAnyMap(wire["subtask_outcome"]) ?: wire)
    .takeIf { candidate ->
      val candidateIssueKey = candidate[SharedPayloadKeys.ISSUE_KEY]?.toString()?.takeIf(String::isNotBlank) ?: issueKey
      val candidateSubtaskId = candidate[SharedPayloadKeys.SUBTASK_ID].asGoalRunnerIntOrNull() ?: subtaskId
      candidateIssueKey == issueKey && candidateSubtaskId == subtaskId
    }?.let { candidate ->
      candidate[SharedPayloadKeys.STATUS]?.toString()?.let(::goalContinuationTerminalStatus)?.let { status ->
        linkedMapOf<String, Any?>(
          SharedPayloadKeys.ISSUE_KEY to issueKey,
          SharedPayloadKeys.SUBTASK_ID to subtaskId,
          SharedPayloadKeys.STATUS to status.wireValue,
          SharedPayloadKeys.WORKFLOW_ID to (
            candidate[SharedPayloadKeys.WORKFLOW_ID]?.toString()?.takeIf(String::isNotBlank) ?: workflowId
          ),
          "last_resumable_step" to (
            candidate["last_resumable_step"]?.toString()?.takeIf(String::isNotBlank) ?: "preplan"
          ),
        ).apply {
          candidate["commit_sha"]?.toString()?.takeIf(String::isNotBlank)?.let { put("commit_sha", it) }
          candidate["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)?.let { put("blocked_reason", it) }
        }.let(FeatureTaskRuntimeWorkflowArtifactMap::from)
      }
    }
}

fun goalContinuationTerminalStatus(status: String?): GoalRunnerTerminalStatus? =
  status?.let(GoalRunnerTerminalStatus::fromWire)

fun goalContinuationOutcome(
  artifacts: Any,
  issueKey: String,
  subtaskId: Int,
  suppressPr: Boolean,
): GoalRunnerStoredOutcome? {
  val outcome =
    artifacts.asGoalWorkflowArtifactMap("goal continuation outcome artifacts")
      .get("goal_continuation_outcome") as? Map<*, *> ?: return null
  if (outcome[SharedPayloadKeys.ISSUE_KEY]?.toString() != issueKey) return null
  if (outcome[SharedPayloadKeys.SUBTASK_ID].asGoalRunnerIntOrNull() != subtaskId) return null
  return goalContinuationTerminalStatus(outcome[SharedPayloadKeys.STATUS]?.toString())?.let { status ->
    GoalRunnerStoredOutcome(
      status = status,
      workflowId = outcome[SharedPayloadKeys.WORKFLOW_ID]?.toString().orEmpty(),
      commitSha = outcome["commit_sha"]?.toString()?.takeIf(String::isNotBlank),
      blockedReason = outcome["blocked_reason"]?.toString()?.takeIf(String::isNotBlank),
      lastResumableStep = outcome["last_resumable_step"]?.toString()?.takeIf(String::isNotBlank),
      suppressPr = suppressPr,
    )
  }
}
