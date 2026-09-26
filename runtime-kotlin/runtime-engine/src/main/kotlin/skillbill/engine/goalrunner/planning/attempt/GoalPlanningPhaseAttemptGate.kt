package skillbill.engine.goalrunner.planning.attempt

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.goalrunner.planning.model.GoalPlanningPhaseProduction
import skillbill.engine.goalrunner.planning.model.GoalPlanningProduceAttemptArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningProducePhaseArgs
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.outcome.malformedReason
import skillbill.engine.goalrunner.planning.outcome.stopped
import skillbill.engine.goalrunner.planning.outcome.unexpectedPlanningFailureReason
import skillbill.engine.goalrunner.planning.outcome.unsuccessfulStatusReason
import skillbill.engine.goalrunner.planning.sweep.DefaultGoalPlanningSweep
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

internal fun DefaultGoalPlanningSweep.producePhase(args: GoalPlanningProducePhaseArgs): GoalPlanningPhaseProduction {
  val attemptArgs = args.attempt
  val phase = attemptArgs.phase
  val shared = phase.shared
  val phaseId = phase.phaseId
  val subtask = phase.subtask
  var priorSchemaFailure = attemptArgs.priorSchemaFailure
  var retryableDeclines = 0
  var attempt = 0
  while (true) {
    attempt += 1
    val scope = planningAttemptScope(shared, phaseId, subtask, attempt)
    recordPlanningAttemptStarted(this, scope)
    val step =
      advancePlanningProduceAttempt(
        PlanningProduceAdvanceArgs(
          attemptArgs = attemptArgs.copy(priorSchemaFailure = priorSchemaFailure),
          scope = scope,
          retryableDeclines = retryableDeclines,
          phaseId = phaseId,
          finalizePayload = args.finalizePayload,
        ),
      )
    when (step) {
      is PlanningProduceStep.Done -> return step.production
      is PlanningProduceStep.RetryDecline -> {
        retryableDeclines = step.retryableDeclines
      }
      is PlanningProduceStep.RetrySchema -> {
        priorSchemaFailure = step.priorSchemaFailure
      }
    }
  }
}

fun DefaultGoalPlanningSweep.gateCapturedPayload(
  captured: GoalPlanningPhaseProduction.Captured,
  phaseId: String,
  finalizePayload: (String) -> String,
): GoalPlanningPhaseProduction {
  val payload = finalizePayload(captured.payload)
  val accepted =
    if (payload == captured.payload) {
      AcceptedFeatureTaskRuntimePhaseOutput(
        normalizedOutput = captured.normalizedOutput,
        repairEvidence = captured.repairEvidence,
      )
    } else {
      outputValidator.validatePhaseOutput(payload, phaseId).requireAcceptedOutput(phaseId)
    }
  val canonicalPayload = accepted.normalizedOutput.canonicalJson
  val gateReason =
    projectionGateReason(canonicalPayload, phaseId)
      ?: return GoalPlanningPhaseProduction.Captured(
        canonicalPayload,
        accepted.normalizedOutput,
        accepted.repairEvidence ?: captured.repairEvidence,
        captured.agentId,
      )
  return GoalPlanningPhaseProduction.SchemaRejected(gateReason, canonicalPayload, captured.agentId)
}

internal fun DefaultGoalPlanningSweep.produceAttemptOrStop(
  args: GoalPlanningProduceAttemptArgs,
): GoalPlanningPhaseProduction =
  runCatching {
    produceAttempt(args)
  }.getOrElse { error ->
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

internal fun DefaultGoalPlanningSweep.validatePlanningAttemptOutput(
  stdout: String,
  shared: GoalPlanningSharedContext,
  subtaskId: Int,
  phaseId: String,
  agentId: String,
): GoalPlanningPhaseProduction =
  runCatching {
    outputValidator.validatePhaseOutput(stdout, phaseId).requireAcceptedOutput(phaseId)
  }.fold(
    onSuccess = { accepted ->
      val payload = accepted.normalizedOutput.envelopeWireMap()
      if (payload[SharedPayloadKeys.STATUS].workflowStepStatus() != WorkflowStepStatus.COMPLETED) {
        val reason = unsuccessfulStatusReason(phaseId, payload)
        val canonical = accepted.normalizedOutput.canonicalJson
        if (FeatureTaskRuntimePhaseSafetyPolicy.dispositionForTerminalOutput(payload).retryOnResume) {
          GoalPlanningPhaseProduction.RetryableDecline(reason, canonical, agentId)
        } else {
          GoalPlanningPhaseProduction.UnsuccessfulStatus(
            reason,
            canonical,
            agentId,
            stopped(shared, subtaskId, reason, phaseId),
          )
        }
      } else {
        GoalPlanningPhaseProduction.Captured(
          accepted.normalizedOutput.canonicalJson,
          accepted.normalizedOutput,
          accepted.repairEvidence,
          agentId,
        )
      }
    },
    onFailure = { error ->
      if (error is InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
        GoalPlanningPhaseProduction.SchemaRejected(
          error.payloadFreeReason ?: "Goal planning phase output was rejected by its schema contract.",
          stdout,
          agentId,
        )
      } else {
        GoalPlanningPhaseProduction.Stopped(
          stopped(shared, subtaskId, malformedReason(phaseId, error), phaseId),
        )
      }
    },
  )
