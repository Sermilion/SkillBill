package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

sealed interface GoalPostChangeValidationResult {
  data object Passed : GoalPostChangeValidationResult

  data class Stopped(val stepId: String, val reason: String) : GoalPostChangeValidationResult
}

/** Runs the full validation definition on a goal's tree after a stage changed it, under the goal's identity. */
fun interface GoalRunnerPostChangeValidation {
  fun run(
    request: PhaseRunRequest,
    parentWorkflowId: String,
    branch: String,
  ): GoalPostChangeValidationResult
}

@Inject
class DefaultGoalRunnerPostChangeValidation(
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
