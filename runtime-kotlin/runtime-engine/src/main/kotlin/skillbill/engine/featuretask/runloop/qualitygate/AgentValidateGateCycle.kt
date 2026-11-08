package skillbill.engine.featuretask.runloop.qualitygate

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.attempt.PhaseQualityGateCycleContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.attempt.RuntimeOwnedGateSettlement
import skillbill.engine.featuretask.slot.attempt.blockGateStep
import skillbill.engine.featuretask.slot.attempt.runAcceptedAttemptLoop
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

internal class AgentValidateGateCycle(
  private val context: PhaseQualityGateCycleContext,
  private val call: PhaseStepCall,
  private val validationCoordinator: FeatureTaskRuntimeValidationGateCoordinator,
) {
  private var stoppedAttempt: PhaseOutcome? = null

  internal fun run(run: PhaseRun): PhaseOutcome {
    call.acceptedExecution.requireAcceptedStep(run, call.strategyId)
    var failed: PhaseOutcome? = null
    repeat(MAX_VALIDATE_ATTEMPTS) {
      val outcome = attempt(run)
      if (outcome.blockedReason == null) return outcome
      failed = outcome
      context.coupledRunTransitions.spendFailedAttempt(
        run.phaseId,
        currentAttempt(run, call.acceptedExecution.nextStepIteration()),
      )
    }
    return exhausted(run, requireNotNull(failed))
  }

  private fun attempt(run: PhaseRun): PhaseOutcome {
    stoppedAttempt = null
    val iteration = call.acceptedExecution.nextStepIteration()
    val cycle =
      validationCoordinator.execute(
        ValidationGateAgentRepairLauncher { findings, _, _ ->
          repair(run, run.copy(validationGateFindings = findings))
        },
      )
    return stoppedAttempt ?: settle(run, iteration, cycle)
  }

  private fun exhausted(
    run: PhaseRun,
    last: PhaseOutcome,
  ): PhaseOutcome {
    val record = currentRecord(run)
    return context.blockGateStep(
      run,
      record?.attemptCount ?: call.acceptedExecution.nextStepIteration(),
      "Validation could not make the build green after $MAX_VALIDATE_ATTEMPTS attempts. " +
        "Last attempt: ${last.blockedReason}",
      record?.failureDisposition ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      last.failureCode,
    )
  }

  private fun currentAttempt(
    run: PhaseRun,
    fallback: Int,
  ): Int = currentRecord(run)?.attemptCount ?: fallback

  private fun currentRecord(run: PhaseRun): FeatureTaskRuntimePhaseRecord? =
    context.recorder.loadPhaseRecords(context.request.workflowId)?.get(run.phaseId)

  private fun repair(
    acceptedRun: PhaseRun,
    run: PhaseRun,
  ): ValidationGateAgentRepairResult {
    val attemptCall = context.gateAttemptCall(call, acceptedRun, run)
    val settled = context.runAcceptedAttemptLoop(run, attemptCall)
    val completed = settled.completedOutput
    val paused = settled.pausedReason
    if (completed == null) stoppedAttempt = settled
    return when {
      completed != null -> ValidationGateAgentRepairResult.Completed(completed)
      paused != null -> ValidationGateAgentRepairResult.Paused(paused)
      else ->
        ValidationGateAgentRepairResult.Blocked(
          settled.blockedReason ?: "Validation phase did not complete.",
          failureCode = settled.failureCode,
        )
    }
  }

  private fun settle(
    run: PhaseRun,
    iteration: Int,
    cycle: ValidationGateCycleResult,
  ): PhaseOutcome =
    when (cycle) {
      is ValidationGateCycleResult.Terminal ->
        when (val terminal = cycle.outcome) {
          is ValidationGateCycleTerminalOutcome.Paused -> PhaseOutcome.paused(terminal.reason)
          is ValidationGateCycleTerminalOutcome.Completed ->
            RuntimeOwnedGateSettlement(context, label = "validation")
              .settle(run, terminal.output.iteration, terminal.output.payload, context.observability)
          is ValidationGateCycleTerminalOutcome.Blocked ->
            context.blockGateStep(
              run,
              currentAttempt(run, iteration),
              terminal.reason,
              terminal.failureDisposition ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
              terminal.failureCode,
            )
        }
    }
}

internal const val MAX_VALIDATE_ATTEMPTS: Int = 3
