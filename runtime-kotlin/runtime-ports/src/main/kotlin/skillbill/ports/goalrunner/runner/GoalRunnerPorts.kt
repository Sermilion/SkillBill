package skillbill.ports.goalrunner.runner

import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
