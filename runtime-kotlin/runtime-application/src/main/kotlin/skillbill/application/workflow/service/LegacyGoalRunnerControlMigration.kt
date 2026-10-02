package skillbill.application.workflow.service

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

internal fun migrateLegacyGoalRunnerControls(
  unitOfWork: GoalRunnerPersistenceSession,
  existing: WorkflowStateSnapshot,
) {
  val artifacts = existing.artifacts
  val controls = unitOfWork.goalRunnerControls
  val legacyPolicy =
    if (controls.reviewPolicy(existing.workflowId) == null) reviewPolicyFromLegacyArtifacts(artifacts) else null
  val durableAcceptances = controls.outOfBandAcceptances(existing.workflowId)
  val legacyAcceptances =
    outOfBandAcceptancesFromLegacyArtifacts(artifacts).filterKeys { it !in durableAcceptances }
  legacyPolicy?.let { controls.persistReviewPolicy(existing.workflowId, it) }
  legacyAcceptances.values.forEach { acceptance ->
    controls.persistOutOfBandAcceptance(existing.workflowId, acceptance)
  }
}

private fun legacyControlSchemaError(message: String): Nothing =
  throw InvalidWorkflowStateSchemaError("Legacy goal runner control artifact: $message")

private fun reviewPolicyFromLegacyArtifacts(artifacts: DurableWorkflowArtifacts): GoalRunnerReviewPolicy? {
  val artifactFamily = DurableWorkflowArtifactFamily.GOAL_REVIEW_POLICY
  val raw = artifactFamily.value(artifacts) ?: return null
  val policy =
    JsonCodec.anyToStringAnyMap(raw)
      ?: legacyControlSchemaError("review policy artifact '${artifactFamily.label()}' must be a map.")
  val allowedKeys =
    setOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.PARALLEL_REVIEW_AGENT,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION,
    )
  policy.keys.forEach { key ->
    if (key !in allowedKeys) {
      legacyControlSchemaError("review policy artifact '${artifactFamily.label()}' has unsupported field '$key'.")
    }
  }
  val mode =
    policy[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.CODE_REVIEW_MODE] as? String
      ?: legacyControlSchemaError("review policy artifact '${artifactFamily.label()}' is missing code_review_mode.")
  val codeReviewMode =
    try {
      CodeReviewExecutionMode.fromWire(mode)
    } catch (error: IllegalArgumentException) {
      throw InvalidWorkflowStateSchemaError(
        "Legacy goal runner control artifact: review policy artifact has invalid code_review_mode '$mode'.",
        error,
      )
    }
  val agentAddonSelection =
    decodeGoalAgentAddonSelection(
      policy[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.AGENT_ADDON_SELECTION],
    )
  return GoalRunnerReviewPolicy(codeReviewMode, agentAddonSelection)
}

private fun outOfBandAcceptancesFromLegacyArtifacts(
  artifacts: DurableWorkflowArtifacts,
): Map<Int, GoalRunnerOutOfBandAcceptance> {
  val artifactFamily = DurableWorkflowArtifactFamily.GOAL_OUT_OF_BAND_ACCEPTANCE
  val raw = artifactFamily.value(artifacts) ?: return emptyMap()
  val entries =
    raw as? List<*>
      ?: legacyControlSchemaError("acceptance artifact '${artifactFamily.label()}' must be a list.")
  return entries.associate { element ->
    val entry =
      JsonCodec.anyToStringAnyMap(element)
        ?: legacyControlSchemaError("acceptance artifact '${artifactFamily.label()}' entries must be maps.")
    val acceptance =
      GoalRunnerOutOfBandAcceptance(
        subtaskId =
          (entry[SharedPayloadKeys.SUBTASK_ID] as? Number)?.asExactIntOrNull()?.takeIf { it > 0 }
            ?: legacyControlSchemaError("acceptance artifact entry subtask_id must be a positive integer."),
        commitSha = requiredAcceptanceString(entry, DecompositionManifestPayloadKeys.COMMIT_SHA),
        reason =
          requiredAcceptanceString(entry, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTANCE_REASON),
        acceptedAt =
          requiredAcceptanceString(entry, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ACCEPTED_AT),
      )
    acceptance.subtaskId to acceptance
  }
}

private fun requiredAcceptanceString(
  entry: Map<String, Any?>,
  key: String,
): String =
  (entry[key] as? String)?.takeIf(String::isNotBlank)
    ?: legacyControlSchemaError("acceptance artifact entry is missing a nonblank $key.")

private fun addonSelectionError(
  message: String,
  cause: Throwable? = null,
): Nothing =
  throw SkillBillRuntimeException(AgentAddonFailureCode.INVALID_SELECTION, "Goal review policy $message", cause)

private fun decodeGoalAgentAddonSelection(raw: Any?): AgentAddonSelection {
  val values = raw ?: return AgentAddonSelection()
  val entries = values as? List<*> ?: addonSelectionError("agent_addon_selection must be a list.")
  val decoded = entries.mapIndexed(::decodeGoalAgentAddonSelectionEntry)
  return try {
    AgentAddonSelection(decoded)
  } catch (error: IllegalArgumentException) {
    addonSelectionError("agent_addon_selection is invalid: ${error.message}", error)
  }
}

private fun decodeGoalAgentAddonSelectionEntry(
  index: Int,
  value: Any?,
): PersistedAgentAddonSelectionEntry {
  val entry =
    JsonCodec.anyToStringAnyMap(value)
      ?: addonSelectionError("agent_addon_selection entry $index must be a map.")
  val expectedKeys =
    setOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
    )
  if (entry.keys != expectedKeys) {
    addonSelectionError("agent_addon_selection entry $index has invalid fields.")
  }
  val slug = requiredAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG)
  val sourceIdentity =
    requiredAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY)
  val contentSha256 =
    requiredAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256)
  return try {
    PersistedAgentAddonSelectionEntry(slug, sourceIdentity, contentSha256)
  } catch (error: IllegalArgumentException) {
    addonSelectionError("agent_addon_selection entry $index is invalid: ${error.message}", error)
  }
}

private fun requiredAddonField(
  entry: Map<String, Any?>,
  index: Int,
  key: String,
): String = entry[key] as? String ?: addonSelectionError("add-on entry $index is missing $key.")
