package skillbill.application.agentrun

import me.tatarka.inject.annotations.Inject
import skillbill.application.agentrun.model.AgentRunStartRequest
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest

@Inject
class AgentRunGoalRunnerSubtaskLauncher(
  private val agentRunService: AgentRunService,
) : GoalRunnerSubtaskLauncher {
  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome =
    agentRunService.launch(
      AgentRunStartRequest(
        invokedAgentId = request.invokedAgentId,
        configuredAgentOverrideId = request.configuredAgentOverrideId,
        skillRunRequest = request.skillRunRequest,
        selectedProviderId = request.selectedProviderId,
      ),
    ).launchOutcome
}
