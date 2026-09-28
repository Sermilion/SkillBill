package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal const val AGENT_ADDON_DECLARED_CONSUMER_SEAM = "agent_addon_manifest_consumers"
internal const val AGENT_ADDON_PERSISTED_CONSUMER_SEAM = "agent_addon_persisted_selection_consumers"
private const val RETIRED_BILL_FEATURE_CONSUMER = "bill-feature"

internal fun decodeAgentAddonConsumer(
  id: String,
  seam: String,
  diagnostics: RuntimeDiagnostics?,
): AgentAddonConsumer {
  val decoding = AgentAddonConsumer.decode(id)
  decoding.legacyId?.let { legacyId ->
    val recordedLegacyId = if (legacyId == RETIRED_BILL_FEATURE_CONSUMER) RETIRED_BILL_FEATURE_CONSUMER else legacyId
    diagnostics?.warning(agentAddonConsumerMigrationRecord(seam, recordedLegacyId, decoding.consumer))
  }
  return decoding.consumer
}

internal fun agentAddonConsumerMigrationRecord(
  seam: String,
  legacyId: String,
  consumer: AgentAddonConsumer,
): String =
  "skillbill agent-addon: record_kind=migration; seam=$seam; value_used=${consumer.id}; " +
    "value_expected=${consumer.id}; cause=legacy_consumer_$legacyId"
