package skillbill.engine.goalrunner.manifest

import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict

sealed interface GoalRunnerChildWorkflowSaveResult {
  data class Saved(
    val state: GoalRunnerManifestState,
  ) : GoalRunnerChildWorkflowSaveResult

  data class Conflicted(
    val conflict: GoalPlanningPreparationConflict,
  ) : GoalRunnerChildWorkflowSaveResult
}
