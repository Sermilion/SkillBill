package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection

internal data class PersistedAgentAddonSelectionVerifyRequest(
  val selection: AgentAddonSelection,
  val consumer: AgentAddonConsumer,
  val receivingAgentIds: List<String>,
  val parseAgent: (String) -> String,
  val validateCompatibility: (
    slug: String,
    consumers: List<AgentAddonConsumer>,
    agents: List<String>,
    consumer: AgentAddonConsumer,
    receivingAgents: List<String>,
  ) -> Unit,
  val stringList: (Map<*, *>, String) -> List<String>,
  val decodeConsumer: (String) -> AgentAddonConsumer,
)
