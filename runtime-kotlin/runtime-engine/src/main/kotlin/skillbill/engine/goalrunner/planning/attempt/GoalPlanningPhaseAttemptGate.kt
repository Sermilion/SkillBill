package skillbill.engine.goalrunner.planning.attempt

import me.tatarka.inject.annotations.Inject
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.engine.goalrunner.execution.core.EmptyOrStoppedArgs
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptRecordArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptScope
import skillbill.engine.goalrunner.planning.model.GoalPlanningBurstSchedule
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.emptyOrStopped
import skillbill.engine.goalrunner.planning.outcome.launchedAgentId
import skillbill.engine.goalrunner.planning.outcome.projectionRejectedReason
import skillbill.engine.goalrunner.planning.outcome.stdoutFor
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.outcome.unexpectedPlanningFailureReason
import skillbill.engine.goalrunner.planning.remedies.GoalPlanningRejectionRecorder
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import java.time.Duration as JavaDuration

@Inject
class GoalPlanningPhaseAttemptGate(
  private val manifestStore: GoalRunnerManifestStore,
  private val planningAttemptRecorder: GoalPlanningAttemptRecorder,
  private val planningRejectionRecorder: GoalPlanningRejectionRecorder,
  private val timingPort: RuntimeTimingPort,
  private val burstSchedule: GoalPlanningBurstSchedule,
  private val clock: Clock,
) {
  internal fun producePhase(args: GoalPlanningProduceAttemptArgs): GoalPlanningPhaseProduction {
    val phase = args.phase
    var attempt = 0
    while (true) {
      attempt += 1
      val scope = planningAttemptScope(phase.shared, phase.phaseId, phase.subtask, attempt)
      recordPlanningAttemptStarted(planningAttemptRecorder, scope)
      val production = produceAttemptOrStop(args.copy(attempt = attempt))
      settlePlanningProduction(scope, production)?.let { return it }
    }
  }

  private fun produceAttemptOrStop(args: GoalPlanningProduceAttemptArgs): GoalPlanningPhaseProduction =
    runCatching {
      produceAttempt(args)
    }.getOrElse { error ->
      error.rethrowIfCooperativeCancellationOrInterruption()
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

  private fun produceAttempt(args: GoalPlanningProduceAttemptArgs): GoalPlanningPhaseProduction {
    val phase = args.phase
    val shared = phase.shared
    val subtaskId = phase.subtask?.id ?: 0
    return planningPauseOutcome(shared, subtaskId, phase.phaseId)
      ?: produceAttemptAfterPauseCheck(args, shared, phase.phaseId, subtaskId)
  }

  internal fun planningPauseOutcome(
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

  private fun interruptibleWait(
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

  internal fun produceAttemptAfterPauseCheck(
    args: GoalPlanningProduceAttemptArgs,
    shared: GoalPlanningSharedContext,
    phaseId: String,
    currentSubtaskId: Int,
  ): GoalPlanningPhaseProduction {
    val prompt =
      try {
        composePlanningPrompt(args) { return GoalPlanningPhaseProduction.RequiredWriteRejected(it) }
      } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
        return GoalPlanningPhaseProduction.Stopped(
          stopped(shared, currentSubtaskId, projectionRejectedReason(phaseId, error), phaseId),
        )
      }
    return launchedPlanningProduction(args, shared, phaseId, currentSubtaskId, prompt)
  }

  private fun launchedPlanningProduction(
    args: GoalPlanningProduceAttemptArgs,
    shared: GoalPlanningSharedContext,
    phaseId: String,
    currentSubtaskId: Int,
    prompt: String,
  ): GoalPlanningPhaseProduction {
    val startedAt = clock.instant()
    val outcome = launchPlanningAttempt(args.phase, prompt, manifestStore)
    if (outcome is AgentRunLaunchDenied) {
      return planningPauseOutcome(shared, currentSubtaskId, phaseId, outcome.pauseReason)
        ?: error("planning pause outcome was unexpectedly absent")
    }
    val durationMs = JavaDuration.between(startedAt, clock.instant()).toMillis()
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

  private fun settlePlanningProduction(
    scope: GoalPlanningAttemptScope,
    production: GoalPlanningPhaseProduction,
  ): GoalPlanningPhaseProduction? =
    when (production) {
      is GoalPlanningPhaseProduction.Stopped -> {
        recordPlanningAttempt(planningAttemptRecorder, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
        production
      }
      is GoalPlanningPhaseProduction.RequiredWriteRejected -> production
      is GoalPlanningPhaseProduction.Captured -> {
        recordPlanningAttempt(
          planningAttemptRecorder,
          GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.SUCCEEDED),
        )
        production
      }
      is GoalPlanningPhaseProduction.EmptyProviderTurn -> {
        recordPlanningAttempt(planningAttemptRecorder, GoalPlanningAttemptRecordArgs(scope, GoalProgressOutcome.FAILED))
        recordEmptyProviderTurn(planningRejectionRecorder, scope, production)
        val wait = burstSchedule.emptyTurnBackoffAfterAttempt(scope.attempt)
        interruptibleWait(wait, scope.shared, scope.subtask?.id ?: 0, scope.phaseId)
          ?.let { stoppedOutcome -> GoalPlanningPhaseProduction.Stopped(stoppedOutcome) }
      }
    }
}
