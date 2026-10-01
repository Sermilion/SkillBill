package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.goalrunner.execution.core.EmptyOrStoppedArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.outcome.emptyOrStopped
import skillbill.engine.goalrunner.planning.outcome.launchedAgentId
import skillbill.engine.goalrunner.planning.outcome.projectionRejectedReason
import skillbill.engine.goalrunner.planning.outcome.stdoutFor
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweepConstants
import skillbill.error.goalrunner.GoalRunnerLaunchAuthorizationDeniedException
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError

internal fun DefaultGoalPlanningSweep.produceAttemptAfterPauseCheck(
  args: GoalPlanningProduceAttemptArgs,
  shared: GoalPlanningSharedContext,
  phaseId: String,
  currentSubtaskId: Int,
): GoalPlanningPhaseProduction {
  val prompt =
    runCatching { composePlanningPrompt(args) }.getOrElse { error ->
      if (error !is InvalidFeatureTaskRuntimeHandoffProjectionError) {
        throw error
      }
      return GoalPlanningPhaseProduction.Stopped(
        stopped(shared, currentSubtaskId, projectionRejectedReason(phaseId, error), phaseId),
      )
    }
  val startedAtNanos = System.nanoTime()
  val outcome =
    runCatching { launchPlanningAttempt(args.phase, prompt) }
      .getOrElse { error ->
        if (error is GoalRunnerLaunchAuthorizationDeniedException) {
          return planningPauseOutcome(shared, currentSubtaskId, phaseId, error.pauseReason)
            ?: error("planning pause outcome was unexpectedly absent")
        }
        throw error
      }
  val durationMs = (System.nanoTime() - startedAtNanos) / GoalPlanningSweepConstants.NANOS_PER_MILLI
  val stdout =
    stdoutFor(outcome) ?: return emptyOrStopped(
      EmptyOrStoppedArgs(
        outcome = outcome,
        shared = shared,
        request = args.phase.request,
        currentSubtaskId = currentSubtaskId,
        phaseId = phaseId,
        durationMs = durationMs,
      ),
    )
  return GoalPlanningPhaseProduction.Captured(stdout, launchedAgentId(outcome))
}
