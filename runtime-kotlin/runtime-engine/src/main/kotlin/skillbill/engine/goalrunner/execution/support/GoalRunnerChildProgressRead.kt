package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress

internal sealed interface GoalRunnerChildProgressRead {
  data object Absent : GoalRunnerChildProgressRead

  data class Present(val progress: GoalRunnerWorkflowProgress?) : GoalRunnerChildProgressRead

  data class Failed(val error: Exception) : GoalRunnerChildProgressRead
}
