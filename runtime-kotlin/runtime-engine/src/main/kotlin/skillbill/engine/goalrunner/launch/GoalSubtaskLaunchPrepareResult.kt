package skillbill.engine.goalrunner.launch

import skillbill.engine.goalrunner.execution.support.PreparedLaunch
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict

internal sealed interface GoalSubtaskLaunchPrepareResult {
  data class Prepared(
    val launch: PreparedLaunch,
  ) : GoalSubtaskLaunchPrepareResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalSubtaskLaunchPrepareResult
}
