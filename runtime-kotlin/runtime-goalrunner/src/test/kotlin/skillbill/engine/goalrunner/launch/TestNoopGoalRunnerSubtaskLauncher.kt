package skillbill.engine.goalrunner.launch

import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest

internal object TestNoopGoalRunnerSubtaskLauncher : GoalRunnerSubtaskLauncher {
  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome =
    agentRunLaunchFacts(
      agent = SupportedAgent.CLAUDE,
      stdout = "",
      stderr = "",
    )
}
