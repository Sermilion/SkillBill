package skillbill.cli.kernel.agent

import com.github.ajalt.clikt.core.UsageError
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.agentaddon.AGENT_ADDON_SELECTION_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeGoalContinuationLaunchTokens

internal fun parseAgentAddonSelection(raw: String?): AgentAddonSelection {
  if (raw == null) return AgentAddonSelection()
  val root =
    JsonCodec.parseObjectOrNull(raw)
      ?: invalidAgentAddonSelection(
        "${FeatureTaskRuntimeGoalContinuationLaunchTokens.AGENT_ADDON_SELECTION_JSON_FLAG} must be a JSON object.",
      )
  val map =
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(root))
      ?: invalidAgentAddonSelection(
        "${FeatureTaskRuntimeGoalContinuationLaunchTokens.AGENT_ADDON_SELECTION_JSON_FLAG} must decode to an object.",
      )
  if (
    map.keys !=
    setOf(
      SharedPayloadKeys.CONTRACT_VERSION,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ENTRIES,
    ) ||
    map[SharedPayloadKeys.CONTRACT_VERSION] != AGENT_ADDON_SELECTION_CONTRACT_VERSION
  ) {
    invalidAgentAddonSelection(
      "Agent add-on selection must contain only contract_version=$AGENT_ADDON_SELECTION_CONTRACT_VERSION and entries.",
    )
  }
  val entries =
    map[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ENTRIES] as? List<*>
      ?: invalidAgentAddonSelection("Agent add-on selection entries must be an ordered array.")
  val persistedKeys =
    setOf(
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY,
      FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256,
    )
  val parsedEntries =
    entries.mapIndexed { index, valueEntry ->
      val entry =
        JsonCodec.anyToStringAnyMap(valueEntry)
          ?: invalidAgentAddonSelection("Agent add-on selection entry $index must be an object.")
      if (entry.keys != persistedKeys) {
        invalidAgentAddonSelection("Agent add-on selection entry $index has unsupported or missing fields.")
      }
      val slug =
        entry[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SLUG] as? String
          ?: invalidAgentAddonSelection("Entry $index slug is required.")
      val sourceIdentity =
        entry[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_SOURCE_IDENTITY] as? String
          ?: invalidAgentAddonSelection("Entry $index source_identity is required.")
      val contentSha256 =
        entry[FeatureTaskRuntimeGoalContinuationArtifactPayloadKeys.ADDON_CONTENT_SHA256] as? String
          ?: invalidAgentAddonSelection("Entry $index content_sha256 is required.")
      val violation = PersistedAgentAddonSelectionEntry.violation(slug, sourceIdentity, contentSha256)
      if (violation != null) {
        invalidAgentAddonSelection("Invalid agent add-on selection: $violation")
      }
      PersistedAgentAddonSelectionEntry(slug, sourceIdentity, contentSha256)
    }
  val selectionViolation = AgentAddonSelection.violation(parsedEntries)
  if (selectionViolation != null) {
    invalidAgentAddonSelection("Invalid agent add-on selection: $selectionViolation")
  }
  return AgentAddonSelection(parsedEntries)
}

internal fun invalidAgentAddonSelection(
  message: String,
  cause: Throwable? = null,
): Nothing {
  throw UsageError(message).apply { cause?.let(::initCause) }
}
