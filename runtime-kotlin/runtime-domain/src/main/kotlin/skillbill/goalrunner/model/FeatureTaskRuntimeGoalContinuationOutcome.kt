package skillbill.goalrunner.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

data class FeatureTaskRuntimeGoalContinuationOutcome(
  val issueKey: String,
  val subtaskId: Int,
  val status: GoalRunnerTerminalStatus,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String,
  val finalizingAgentId: String? = null,
  val participatingAgentIds: List<String> = emptyList(),
) {
  constructor(
    issueKey: String,
    subtaskId: Int,
    status: String,
    workflowId: String,
    commitSha: String? = null,
    blockedReason: String? = null,
    lastResumableStep: String,
    finalizingAgentId: String? = null,
    participatingAgentIds: List<String> = emptyList(),
  ) : this(
    issueKey = issueKey,
    subtaskId = subtaskId,
    status =
      requireNotNull(GoalRunnerTerminalStatus.fromWire(status)) {
        "Unknown goal-continuation outcome status '$status'."
      },
    workflowId = workflowId,
    commitSha = commitSha,
    blockedReason = blockedReason,
    lastResumableStep = lastResumableStep,
    finalizingAgentId = finalizingAgentId,
    participatingAgentIds = participatingAgentIds,
  )

  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.issueKey must be non-blank." }
    require(subtaskId > 0) { "FeatureTaskRuntimeGoalContinuationOutcome.subtaskId must be positive." }
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.workflowId must be non-blank." }
    require(lastResumableStep.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationOutcome.lastResumableStep must be non-blank."
    }
  }

  fun toPersistenceWire(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.ISSUE_KEY to issueKey,
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      SharedPayloadKeys.STATUS to status.wireValue,
      SharedPayloadKeys.WORKFLOW_ID to workflowId,
      "last_resumable_step" to lastResumableStep,
      "participating_agent_ids" to participatingAgentIds,
    ).apply {
      commitSha?.let { put("commit_sha", it) }
      blockedReason?.let { put("blocked_reason", it) }
      finalizingAgentId?.let { put("finalizing_agent_id", it) }
    }

  companion object {
    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationOutcome {
      val reader = durableArtifactMapReader(raw)
      return try {
        FeatureTaskRuntimeGoalContinuationOutcome(
          issueKey = reader.requiredString("issue_key"),
          subtaskId = reader.requiredInt("subtask_id"),
          status =
            requireNotNull(GoalRunnerTerminalStatus.fromWire(reader.requiredString("status"))) {
              "Unknown goal-continuation outcome status '${raw[SharedPayloadKeys.STATUS]}'."
            },
          workflowId = reader.requiredString("workflow_id"),
          commitSha = reader.optionalString("commit_sha"),
          blockedReason = reader.optionalString("blocked_reason"),
          lastResumableStep = reader.requiredString("last_resumable_step"),
          finalizingAgentId = reader.optionalString("finalizing_agent_id"),
          participatingAgentIds = reader.optionalStringList("participating_agent_ids"),
        )
      } catch (error: IllegalArgumentException) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime goal-continuation outcome is invalid.",
          error,
        )
      }
    }
  }
}
