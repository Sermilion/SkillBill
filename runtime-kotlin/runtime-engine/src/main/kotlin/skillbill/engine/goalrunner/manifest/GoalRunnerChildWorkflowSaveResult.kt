package skillbill.engine.goalrunner.manifest

import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict

public sealed interface GoalRunnerChildWorkflowSaveResult {
  public data class Saved(
    public val state: GoalRunnerManifestState,
  ) : GoalRunnerChildWorkflowSaveResult

  public data class Conflicted(
    public val conflict: GoalPlanningPreparationConflict,
  ) : GoalRunnerChildWorkflowSaveResult
}
