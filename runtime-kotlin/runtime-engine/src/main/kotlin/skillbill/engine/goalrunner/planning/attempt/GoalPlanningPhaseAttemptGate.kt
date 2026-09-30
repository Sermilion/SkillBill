package skillbill.engine.goalrunner.planning.attempt

import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.outcome.unexpectedPlanningFailureReason
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.time.model.RuntimeWaitResult
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

internal fun DefaultGoalPlanningSweep.producePhase(args: GoalPlanningProduceAttemptArgs): GoalPlanningPhaseProduction {
  val phase = args.phase
  var attempt = 0
  while (true) {
    attempt += 1
    val scope = planningAttemptScope(phase.shared, phase.phaseId, phase.subtask, attempt)
    recordPlanningAttemptStarted(this, scope)
    val production = produceAttemptOrStop(args.copy(attempt = attempt))
    settlePlanningProduction(scope, production)?.let { return it }
  }
}

internal fun DefaultGoalPlanningSweep.produceAttemptOrStop(
  args: GoalPlanningProduceAttemptArgs,
): GoalPlanningPhaseProduction =
  runCatching {
    produceAttempt(args)
  }.getOrElse { error ->
    if (error is RequiredPhaseWriteRejected || error is CancellationException) throw error
    val phase = args.phase
    GoalPlanningPhaseProduction.Stopped(
      stopped(
        phase.shared,
        phase.subtask?.id ?: 0,
        unexpectedPlanningFailureReason(phase.phaseId, error),
        phase.phaseId,
      ),
    )
  }

internal fun DefaultGoalPlanningSweep.produceAttempt(
  args: GoalPlanningProduceAttemptArgs,
): GoalPlanningPhaseProduction {
  val phase = args.phase
  val shared = phase.shared
  val subtask = phase.subtask
  val phaseId = phase.phaseId
  val currentSubtaskId = subtask?.id ?: 0
  return planningPauseOutcome(shared, currentSubtaskId, phaseId)
    ?: produceAttemptAfterPauseCheck(args, shared, phaseId, currentSubtaskId)
}

internal fun DefaultGoalPlanningSweep.planningPauseOutcome(
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  phaseId: String,
  pauseReason: String? = null,
): GoalPlanningPhaseProduction.Stopped? {
  val controls = manifestStore.controlState(shared.parentWorkflowId)
  if (!controls.requiresPauseBoundary(shared.manifest)) return null
  val reason = pauseReason?.let { " (reason=$it)" }.orEmpty()
  return GoalPlanningPhaseProduction.Stopped(
    stopped(
      shared,
      subtaskId,
      "Goal planning reached a durable pause boundary before launching phase '$phaseId'$reason.",
      phaseId,
      GoalRunnerStopReason.PAUSED,
    ),
  )
}

internal fun DefaultGoalPlanningSweep.interruptibleWait(
  duration: Duration,
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  phaseId: String,
): GoalPlanningSweepOutcome.Stopped? {
  if (duration <= ZERO) return null
  var remaining = duration
  while (remaining > ZERO) {
    planningPauseOutcome(shared, subtaskId, phaseId)?.let { return it.outcome }
    val slice = remaining.coerceAtMost(burstSchedule.waitSlice)
    when (timingPort.wait(slice)) {
      RuntimeWaitResult.COMPLETED -> remaining -= slice
      RuntimeWaitResult.INTERRUPTED -> return stopped(
        shared,
        subtaskId,
        "Goal planning wait was interrupted before launching phase '$phaseId'.",
        phaseId,
      )
    }
  }
  return planningPauseOutcome(shared, subtaskId, phaseId)?.outcome
}
