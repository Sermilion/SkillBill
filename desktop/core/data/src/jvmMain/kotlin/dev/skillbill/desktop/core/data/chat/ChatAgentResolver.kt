package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.Tier
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ConfigRead

/** The execution's own agent, else the launch default, with its reasoning-tier model from the machine config. */
internal class ChatAgentResolver(
  private val machineConfig: suspend () -> ConfigRead<MachineConfig>,
  private val diagnostics: Diagnostics,
) {
  suspend fun resolve(item: WorkItem, detail: Detail?, runOptions: RunOptions?): ChatAgent? {
    val own = detail?.activeAgent?.takeIf(String::isNotBlank) ?: item.agentId?.takeIf(String::isNotBlank)
    val agentId = own
      ?: (runOptions?.agentId?.takeIf(String::isNotBlank) ?: runOptions?.agentChoices?.firstOrNull(String::isNotBlank))
        ?.also { diagnostics.record("chat.agent_fallback") }
      ?: return null
    return forAgent(agentId)
  }

  suspend fun forAgent(agentId: String): ChatAgent {
    val document = (machineConfig() as? ConfigRead.Document)?.document as? ConfigDocument.Present
    val directive = document?.values?.executionMatrix?.agents?.get(agentId)?.get(Tier.REASONING)
    if (directive == null) diagnostics.record("chat.matrix_fallback")
    return ChatAgent(agentId, directive?.model, directive?.effort)
  }
}
