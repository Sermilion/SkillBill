package skillbill.engine.featuretask.runloop.qualitygate

import skillbill.application.decomposition.baseBranch
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.attempt.PhaseQualityGateCycleContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.attempt.RuntimeOwnedGateSettlement
import skillbill.engine.featuretask.slot.attempt.blockGateStep
import skillbill.engine.featuretask.slot.attempt.gateChangedPaths
import skillbill.engine.featuretask.slot.attempt.runAcceptedAttemptLoop
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.engine.featuretask.validation.ReadinessPostValidateCaptureRequest
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind

private const val DEFAULT_BASE_BRANCH = "main"

internal class AgentValidateGateCycle(
  private val context: PhaseQualityGateCycleContext,
  private val call: PhaseStepCall,
  private val validationCoordinator: FeatureTaskRuntimeValidationGateCoordinator,
) {
  private var stoppedAttempt: PhaseOutcome? = null

  internal fun run(run: PhaseRun): PhaseOutcome {
    call.acceptedExecution.requireAcceptedStep(run, call.strategyId)
    val iteration = call.acceptedExecution.nextStepIteration()
    val cycle =
      validationCoordinator.execute(
        ValidationGateAgentRepairLauncher { findings, _, _ ->
          repair(run, run.copy(validationGateFindings = findings))
        },
      )
    return stoppedAttempt ?: settle(run, iteration, cycle)
  }

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
            RuntimeOwnedGateSettlement(context, label = "validation", afterCompleted = ::captureReadinessFragment)
              .settle(run, terminal.output.iteration, terminal.output.payload, context.observability)
          is ValidationGateCycleTerminalOutcome.Blocked ->
            context.blockGateStep(
              run,
              context.recorder
                .loadPhaseRecords(context.request.workflowId)
                ?.get(run.phaseId)
                ?.attemptCount
                ?: iteration,
              terminal.reason,
              terminal.failureDisposition ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
              context.observability,
            )
        }
    }

  private fun captureReadinessFragment(run: PhaseRun) {
    if (context.request.skeletonDefinition?.runStateKind == SkeletonRunStateKind.IN_MEMORY) return
    context.readinessGateCoordinator.capturePostValidateFragment(
      ReadinessPostValidateCaptureRequest(
        workflowId = context.request.workflowId,
        repoRoot = context.request.repoRoot,
        baseBranch =
          context.recorder.loadResolvedBranch(context.request.workflowId)?.baseBranch ?: DEFAULT_BASE_BRANCH,
        changedPaths = context.gateChangedPaths(run),
        gitOperations = context.gitOperations,
      ),
    )
  }
}
