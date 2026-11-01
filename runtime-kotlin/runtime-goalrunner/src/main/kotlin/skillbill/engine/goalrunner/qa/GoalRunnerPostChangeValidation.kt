package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

public sealed interface GoalPostChangeValidationResult {
  public data object Passed : GoalPostChangeValidationResult

  public data class Stopped(public val stepId: String, public val reason: String) : GoalPostChangeValidationResult
}

/** Runs the full validation definition on a goal's tree after a stage changed it, under the goal's identity. */
public fun interface GoalRunnerPostChangeValidation {
  public fun run(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    branch: String,
  ): GoalPostChangeValidationResult
}

@Inject
public class DefaultGoalRunnerPostChangeValidation(
  private val phaseRunEntry: PhaseRunEntry,
) : GoalRunnerPostChangeValidation {
  override fun run(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    branch: String,
  ): GoalPostChangeValidationResult =
    when (
      val result =
        phaseRunEntry.runForGoal(
          request.copy(definitionId = SkeletonDefinition.VALIDATION.id, recordedGoalQaOutcome = null),
          parentWorkflowId,
          branch,
        )
    ) {
      is PhaseRunResult.Blocked -> GoalPostChangeValidationResult.Stopped(result.stepId, result.reason)
      is PhaseRunResult.Completed -> GoalPostChangeValidationResult.Passed
    }
}
