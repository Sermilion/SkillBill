package skillbill.engine.goalrunner.execution.support

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalrunner.model.GoalContinuationCandidate
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.asGoalRunnerIntOrNull
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily

fun List<GoalContinuationCandidate>.authoritativeOutcomesBySubtask(): Map<Int, GoalRunnerStoredOutcome> =
  groupBy { candidate -> candidate.goalContinuation.subtaskId }
    .mapNotNull { (subtaskId, candidates) ->
      candidates.selectAuthoritativeOutcome()?.let { outcome -> subtaskId to outcome }
    }
    .toMap()

fun List<GoalContinuationCandidate>.selectAuthoritativeOutcome(): GoalRunnerStoredOutcome? {
  val completeWinner =
    asSequence()
      .filter { candidate -> candidate.outcome?.status == GoalRunnerTerminalStatus.COMPLETE }
      .maxWithOrNull(compareBy<GoalContinuationCandidate> { it.snapshot.updatedAt }.thenBy { it.snapshot.workflowId })
  if (completeWinner != null) {
    return completeWinner.outcome
  }
  val fallbackWinner =
    asSequence()
      .filter { candidate -> candidate.outcome != null }
      .maxWithOrNull(compareBy<GoalContinuationCandidate> { it.snapshot.updatedAt }.thenBy { it.snapshot.workflowId })
  return fallbackWinner?.outcome
}

fun staleRunningReason(
  staleWorkflowId: String,
  issueKey: String,
  subtaskId: Int,
  authoritative: GoalRunnerStoredOutcome?,
): String =
  authoritative?.let { outcome ->
    if (outcome.workflowId == staleWorkflowId) {
      "Goal status reconciliation closed inactive running child '$staleWorkflowId' for issue '$issueKey' " +
        "subtask $subtaskId because a terminal outcome was already durable."
    } else {
      "Goal status reconciliation closed stale running child '$staleWorkflowId' for issue '$issueKey' " +
        "subtask $subtaskId in favor of authoritative ${outcome.status.wireValue} workflow " +
        "'${outcome.workflowId}'."
    }
  } ?: (
    "Goal status reconciliation closed stale running child '$staleWorkflowId' for issue '$issueKey' " +
      "subtask $subtaskId because it was no longer active."
  )

fun Map<String, Any?>.matchesGoalContinuation(
  issueKey: String,
  subtaskId: Int,
): Boolean {
  val candidateIssueKey = this[SharedPayloadKeys.ISSUE_KEY]?.toString()?.takeIf(String::isNotBlank) ?: issueKey
  val candidateSubtaskId = this[SharedPayloadKeys.SUBTASK_ID].asGoalRunnerIntOrNull() ?: subtaskId
  return candidateIssueKey == issueKey && candidateSubtaskId == subtaskId
}

fun Map<String, Any?>.toMissingResultPrefixOutcomeArtifact(
  issueKey: String,
  subtaskId: Int,
  workflowId: String,
  status: GoalRunnerTerminalStatus,
): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    SharedPayloadKeys.ISSUE_KEY to issueKey,
    SharedPayloadKeys.SUBTASK_ID to subtaskId,
    SharedPayloadKeys.STATUS to status.toGoalContinuationWireStatus(),
    SharedPayloadKeys.WORKFLOW_ID to (
      this[SharedPayloadKeys.WORKFLOW_ID]?.toString()?.takeIf(String::isNotBlank) ?: workflowId
    ),
    "last_resumable_step" to (
      this["last_resumable_step"]?.toString()?.takeIf(String::isNotBlank) ?: "preplan"
    ),
  ).apply {
    this@toMissingResultPrefixOutcomeArtifact["commit_sha"]?.toString()?.takeIf(String::isNotBlank)
      ?.let { put("commit_sha", it) }
    this@toMissingResultPrefixOutcomeArtifact["blocked_reason"]?.toString()?.takeIf(String::isNotBlank)
      ?.let { put("blocked_reason", it) }
  }

fun GoalRunnerTerminalStatus.toGoalContinuationWireStatus(): String = wireValue

fun maxHistorySequence(
  artifacts: Map<String, Any?>,
  historyFamily: DurableWorkflowArtifactFamily,
  current: Int?,
): Int? {
  val entries = (historyFamily.value(artifacts) as? List<*>).orEmpty()
  var max = current
  entries.forEach { item ->
    val sequence = (item as? Map<*, *>)?.get("sequence_number").asGoalRunnerIntOrNull()
    val currentMax = max
    if (sequence != null && (currentMax == null || sequence > currentMax)) {
      max = sequence
    }
  }
  return max
}
