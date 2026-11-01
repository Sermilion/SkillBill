package skillbill.engine.goalrunner.execution.core

import skillbill.engine.goalrunner.model.GoalRunnerChildExecutionPlanAdmission

val DIRECT_GOAL_RUNNER_EXECUTION_COORDINATOR: GoalRunnerExecutionCoordinator =
  object : GoalRunnerExecutionCoordinator {
    override fun <T> runOwnedWithLease(
      parentWorkflowId: String,
      block: (Long, String?) -> T,
    ): GoalRunnerOwnedRun<T> = GoalRunnerOwnedRun.Completed(block(1, null))

    override fun <T> runOwnedWithChildAdmissionLease(
      parentWorkflowId: String,
      childAdmission: GoalRunnerChildExecutionPlanAdmission,
      block: (Long, String?) -> T,
    ): GoalRunnerOwnedRun<T> = GoalRunnerOwnedRun.Completed(block(1, null))

    override fun <T> runOwned(
      parentWorkflowId: String,
      block: () -> T,
    ): GoalRunnerOwnedRun<T> = GoalRunnerOwnedRun.Completed(block())

    override fun <T> runOwnedWithChildAdmission(
      parentWorkflowId: String,
      childAdmission: GoalRunnerChildExecutionPlanAdmission,
      block: () -> T,
    ): GoalRunnerOwnedRun<T> = GoalRunnerOwnedRun.Completed(block())
  }
