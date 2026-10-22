package skillbill.goalrunner

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.blockincident.GoalRunnerAcceptedBlockReferenceKeys
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
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
  acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
): FeatureTaskRuntimeWorkflowArtifactMap? {
  val wire = JsonCodec.anyToStringAnyMap(output) ?: return null
  val candidate = JsonCodec.anyToStringAnyMap(wire["subtask_outcome"]) ?: wire
  val candidateIssueKey = candidate.nonBlankText(SharedPayloadKeys.ISSUE_KEY) ?: issueKey
  val candidateSubtaskId = candidate[SharedPayloadKeys.SUBTASK_ID].asGoalRunnerIntOrNull() ?: subtaskId
  if (candidateIssueKey != issueKey || candidateSubtaskId != subtaskId) return null
  val status = goalContinuationTerminalStatus(candidate[SharedPayloadKeys.STATUS]?.toString()) ?: return null
  val artifact =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.ISSUE_KEY to issueKey,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      SharedPayloadKeys.STATUS to status.wireValue,
      SharedPayloadKeys.WORKFLOW_ID to (candidate.nonBlankText(SharedPayloadKeys.WORKFLOW_ID) ?: workflowId),
      DecompositionManifestPayloadKeys.LAST_RESUMABLE_STEP to (
        candidate.nonBlankText(DecompositionManifestPayloadKeys.LAST_RESUMABLE_STEP) ?: "preplan"
      ),
    )
  candidate.nonBlankText(DecompositionManifestPayloadKeys.COMMIT_SHA)?.let {
    artifact[DecompositionManifestPayloadKeys.COMMIT_SHA] = it
  }
  candidate.nonBlankText(DecompositionManifestPayloadKeys.BLOCKED_REASON)?.let {
    artifact[DecompositionManifestPayloadKeys.BLOCKED_REASON] = it
  }
  if (status == GoalRunnerTerminalStatus.BLOCKED && acceptedBlockReference != null) {
    artifact[GoalRunnerAcceptedBlockReferenceKeys.OUTCOME_REFERENCE] = acceptedBlockReference.toArtifactMap()
  }
  return FeatureTaskRuntimeWorkflowArtifactMap.from(artifact)
}

private fun Map<String, Any?>.nonBlankText(key: String): String? = this[key]?.toString()?.takeIf(String::isNotBlank)

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
    val acceptedBlockReference =
      GoalRunnerAcceptedBlockReference.fromArtifactMap(
        outcome[GoalRunnerAcceptedBlockReferenceKeys.OUTCOME_REFERENCE],
      )
    if (acceptedBlockReference != null && status != GoalRunnerTerminalStatus.BLOCKED) {
      throw invalidWorkflowStateSchemaError("Only blocked goal-continuation outcomes may carry a block reference.")
    }
    GoalRunnerStoredOutcome(
      status = status,
      workflowId = outcome[SharedPayloadKeys.WORKFLOW_ID]?.toString().orEmpty(),
      commitSha = outcome[DecompositionManifestPayloadKeys.COMMIT_SHA]?.toString()?.takeIf(String::isNotBlank),
      blockedReason = outcome[DecompositionManifestPayloadKeys.BLOCKED_REASON]?.toString()?.takeIf(String::isNotBlank),
      lastResumableStep =
        outcome[DecompositionManifestPayloadKeys.LAST_RESUMABLE_STEP]?.toString()?.takeIf(
          String::isNotBlank,
        ),
      suppressPr = suppressPr,
      acceptedBlockReference = acceptedBlockReference,
    )
  }
}
