package skillbill.application.agentrun.model

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest

data class AgentRunStartRequest(
  val invokedAgentId: String,
  val configuredAgentOverrideId: String? = null,
  val skillRunRequest: SkillRunRequest,
  val selectedProviderId: String? = null,
) {
  init {
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { overrideId ->
      require(overrideId.isNotBlank()) { "configuredAgentOverrideId must not be blank when provided." }
    }
    selectedProviderId?.let { providerId ->
      require(providerId.isNotBlank()) { "selectedProviderId must not be blank when provided." }
    }
  }
}

data class AgentRunAgentResolution(
  val invokedAgent: SupportedAgent,
  val configuredOverrideAgent: SupportedAgent?,
  val effectiveAgent: SupportedAgent,
)

data class AgentRunResult(
  val resolution: AgentRunAgentResolution,
  val launchOutcome: AgentRunLaunchOutcome,
)
