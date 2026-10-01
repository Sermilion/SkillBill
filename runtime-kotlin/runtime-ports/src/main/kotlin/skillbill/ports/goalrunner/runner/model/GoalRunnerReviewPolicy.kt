package skillbill.ports.goalrunner.runner.model

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.review.context.model.execution.CodeReviewExecutionMode

data class GoalRunnerReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
  val agentAddonSelection: AgentAddonSelection = AgentAddonSelection(),
)
