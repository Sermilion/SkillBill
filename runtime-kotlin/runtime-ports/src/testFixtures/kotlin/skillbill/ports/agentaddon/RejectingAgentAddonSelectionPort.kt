package skillbill.ports.agentaddon

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import java.nio.file.Path

object RejectingAgentAddonSelectionPort : AgentAddonSelectionPort {
  override fun resolveInitial(
    repoRoot: Path,
    requestedSlugs: List<String>,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
    externalSourceRoots: List<Path>,
  ): HydratedAgentAddonSelection = error("This fixture must not resolve add-ons.")

  override fun verifyPersisted(
    selection: AgentAddonSelection,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection = error("This fixture must not verify add-ons.")
}
