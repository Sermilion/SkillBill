package skillbill.goalrunner.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
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
    val reason = violation(issueKey, subtaskId, workflowId, lastResumableStep)
    require(reason == null) { reason.orEmpty() }
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
    private fun violation(
      issueKey: String,
      subtaskId: Int,
      workflowId: String,
      step: String,
    ): String? =
      when {
        issueKey.isBlank() -> "FeatureTaskRuntimeGoalContinuationOutcome.issueKey must be non-blank."
        subtaskId <= 0 -> "FeatureTaskRuntimeGoalContinuationOutcome.subtaskId must be positive."
        workflowId.isBlank() -> "FeatureTaskRuntimeGoalContinuationOutcome.workflowId must be non-blank."
        step.isBlank() -> "FeatureTaskRuntimeGoalContinuationOutcome.lastResumableStep must be non-blank."
        else -> null
      }

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationOutcome {
      val reader = durableArtifactMapReader(raw)
      val issueKey = reader.requiredString("issue_key")
      val subtaskId = reader.requiredInt("subtask_id")
      val statusWire = reader.requiredString("status")
      val status =
        GoalRunnerTerminalStatus.fromWire(statusWire)
          ?: throw invalidWorkflowStateSchemaError("Feature-task-runtime goal-continuation outcome is invalid.")
      val workflowId = reader.requiredString(SharedPayloadKeys.WORKFLOW_ID)
      val commitSha = reader.optionalString(DecompositionManifestPayloadKeys.COMMIT_SHA)
      val blockedReason = reader.optionalString(DecompositionManifestPayloadKeys.BLOCKED_REASON)
      val step = reader.requiredString(DecompositionManifestPayloadKeys.LAST_RESUMABLE_STEP)
      val finalizingAgentId = reader.optionalString(DecompositionManifestPayloadKeys.FINALIZING_AGENT_ID)
      val participatingAgentIds = reader.optionalStringList(DecompositionManifestPayloadKeys.PARTICIPATING_AGENT_IDS)
      if (violation(issueKey, subtaskId, workflowId, step) != null) {
        throw invalidWorkflowStateSchemaError("Feature-task-runtime goal-continuation outcome is invalid.")
      }
      return FeatureTaskRuntimeGoalContinuationOutcome(
        issueKey = issueKey,
        subtaskId = subtaskId,
        status = status,
        workflowId = workflowId,
        commitSha = commitSha,
        blockedReason = blockedReason,
        lastResumableStep = step,
        finalizingAgentId = finalizingAgentId,
        participatingAgentIds = participatingAgentIds,
      )
    }
  }
}
