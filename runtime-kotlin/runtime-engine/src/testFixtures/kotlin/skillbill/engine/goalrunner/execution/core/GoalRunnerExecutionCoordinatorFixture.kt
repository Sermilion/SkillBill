package skillbill.engine.goalrunner.execution.core

import skillbill.ports.goalrunner.runner.model.GoalRunnerChildExecutionPlanAdmission

val DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR: GoalRunnerExecutionCoordinator =
  object : GoalRunnerExecutionCoordinator {
    override fun <T> runOwned(parentWorkflowId: String, block: () -> T): T = block()

    override fun <T> runOwnedWithChildAdmission(
      parentWorkflowId: String,
      childAdmission: GoalRunnerChildExecutionPlanAdmission,
      block: () -> T,
    ): T = block()
  }
