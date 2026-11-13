package skillbill.application.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.application.agentrun.model.AgentRunAgentResolution
import skillbill.application.agentrun.model.AgentRunResult
import skillbill.application.agentrun.model.AgentRunStartRequest
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.model.AgentRunLaunchRequest

@Inject
class AgentRunService(
  private val agentRunLauncher: AgentRunLauncher,
) {
  fun launch(request: AgentRunStartRequest): AgentRunResult {
    val invokedAgent = SupportedAgent.fromNormalizedId(request.invokedAgentId, label = "invokedAgentId")
    val overrideAgent =
      request.configuredAgentOverrideId
        ?.let { id -> SupportedAgent.fromNormalizedId(id, label = "configuredAgentOverrideId") }
    val selectedProvider =
      request.selectedProviderId
        ?.let { id -> SupportedAgent.fromNormalizedId(id, label = "selectedProviderId") }
    val effectiveAgent = selectedProvider ?: overrideAgent ?: invokedAgent
    val resolution =
      AgentRunAgentResolution(
        invokedAgent = invokedAgent,
        configuredOverrideAgent = overrideAgent,
        effectiveAgent = effectiveAgent,
      )
    return AgentRunResult(
      resolution = resolution,
      launchOutcome =
        agentRunLauncher.launch(
          AgentRunLaunchRequest(
            agentId = effectiveAgent.id,
            skillRunRequest = request.skillRunRequest,
          ),
        ),
    )
  }
}
