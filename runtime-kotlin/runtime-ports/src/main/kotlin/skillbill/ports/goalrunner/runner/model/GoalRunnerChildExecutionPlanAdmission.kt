package skillbill.ports.goalrunner.runner.model

import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan

data class GoalRunnerChildExecutionPlanAdmission(
  val workflowId: String,
  val expected: ValidatedFeatureTaskRuntimeExecutionPlan,
)
