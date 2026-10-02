package skillbill.engine.goalrunner.execution.support

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.LegacyProseWorkflowError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode

fun workflowFamilyFor(
  workflowStates: WorkflowStateRepository,
  workflowId: String,
): WorkflowFamily? {
  val featureTaskRow = workflowStates.getFeatureTaskWorkflow(workflowId)
  if (featureTaskRow != null) {
    return when (featureTaskRow.mode) {
      FeatureTaskWorkflowMode.RUNTIME -> WorkflowFamily.TASK_RUNTIME
      FeatureTaskWorkflowMode.PROSE, null -> throw LegacyProseWorkflowError(workflowId, featureTaskRow.issueKey)
    }
  }
  return if (workflowStates.get(WorkflowFamily.VERIFY, workflowId) != null) {
    WorkflowFamily.VERIFY
  } else {
    null
  }
}

fun decodeGoalAgentAddonSelection(raw: Any?): AgentAddonSelection {
  val values = raw ?: return AgentAddonSelection()
  val entries =
    values as? List<*>
      ?: throw SkillBillRuntimeException(
        AgentAddonFailureCode.INVALID_SELECTION,
        "Goal review policy agent_addon_selection must be a list.",
      )
  return AgentAddonSelection(
    entries.mapIndexed(::decodeGoalAgentAddonSelectionEntry),
  )
}

private fun decodeGoalAgentAddonSelectionEntry(
  index: Int,
  value: Any?,
): PersistedAgentAddonSelectionEntry {
  val entry =
    JsonCodec.anyToStringAnyMap(value)
      ?: throw SkillBillRuntimeException(
        AgentAddonFailureCode.INVALID_SELECTION,
        "Goal review policy agent_addon_selection entry $index must be a map.",
      )
  val expectedKeys =
    setOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
    )
  if (entry.keys != expectedKeys) {
    throw SkillBillRuntimeException(
      AgentAddonFailureCode.INVALID_SELECTION,
      "Goal review policy agent_addon_selection entry $index has invalid fields.",
    )
  }
  return PersistedAgentAddonSelectionEntry(
    requiredAddonField(entry, index, FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG, "slug"),
    requiredAddonField(
      entry,
      index,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      "source_identity",
    ),
    requiredAddonField(
      entry,
      index,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
      "content_sha256",
    ),
  )
}

private fun requiredAddonField(
  entry: Map<String, Any?>,
  index: Int,
  key: String,
  label: String,
): String =
  entry[key] as? String
    ?: throw SkillBillRuntimeException(
      AgentAddonFailureCode.INVALID_SELECTION,
      "Goal review policy add-on entry $index is missing $label.",
    )
