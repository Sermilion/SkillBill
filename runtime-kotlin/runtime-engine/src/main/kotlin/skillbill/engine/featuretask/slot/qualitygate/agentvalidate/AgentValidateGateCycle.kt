package skillbill.engine.featuretask.slot.qualitygate.agentvalidate

import skillbill.application.decomposition.baseBranch
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.qualitygate.RuntimeOwnedGateSettlement
import skillbill.engine.featuretask.slot.qualitygate.blockGateStep
import skillbill.engine.featuretask.slot.qualitygate.gateChangedPaths
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
  private val context: PhaseAttemptEnvironment,
  private val call: PhaseStepCall,
) {
  internal fun run(run: PhaseRun): PhaseOutcome {
    val iteration = call.state.nextStepIteration()
    val cycle =
      context.phaseGates.validationGateCoordinator.execute(
        ValidationGateAgentRepairLauncher { findings, _, _ ->
          repair(run.copy(validationGateFindings = findings))
        },
      )
    return settle(run, iteration, cycle)
  }

  private fun repair(run: PhaseRun): ValidationGateAgentRepairResult {
    val settled = call.state.attemptLoop.run(run, call)
    val completed = settled.completedOutput
    val paused = settled.pausedReason
    return when {
      completed != null -> ValidationGateAgentRepairResult.Completed(completed)
      paused != null -> ValidationGateAgentRepairResult.Paused(paused)
      else ->
        ValidationGateAgentRepairResult.Blocked(
          settled.blockedReason ?: "Validation phase did not complete.",
          failureDisposition = recordedFailureDisposition(run),
        )
    }
  }

  private fun settle(
    run: PhaseRun,
    iteration: Int,
    cycle: ValidationGateCycleResult,
  ): PhaseOutcome =
    when (cycle) {
      ValidationGateCycleResult.AbsentFallback -> {
        context.observability.started(
          run.phaseId,
          run.resolvedAgent.resolvedAgentId,
          iteration,
          run.modelDirective,
          FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
        )
        context.blockGateStep(
          run,
          iteration,
          FeatureTaskRuntimeValidationGateCoordinator.ABSENT_VALIDATION_GATE_REASON,
          FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          context.observability,
        )
      }
      is ValidationGateCycleResult.Terminal ->
        when (val terminal = cycle.outcome) {
          is ValidationGateCycleTerminalOutcome.Paused -> PhaseOutcome.paused(terminal.reason)
          is ValidationGateCycleTerminalOutcome.Completed ->
            RuntimeOwnedGateSettlement(context, label = "validation", afterCompleted = ::captureReadinessFragment)
              .settle(run, terminal.output.iteration, terminal.output.payload, context.observability)
          is ValidationGateCycleTerminalOutcome.Blocked ->
            context.blockGateStep(
              run,
              context.recorder.loadPhaseRecords(context.request.workflowId)?.get(run.phaseId)?.attemptCount
                ?: iteration,
              terminal.reason,
              terminal.failureDisposition ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
              context.observability,
            )
        }
    }

  private fun recordedFailureDisposition(run: PhaseRun): FeatureTaskRuntimeFailureDisposition? =
    context.recorder.loadPhaseRecords(run.request.workflowId)?.get(run.phaseId)?.failureDisposition

  private fun captureReadinessFragment(run: PhaseRun) {
    if (context.request.skeletonDefinition?.runStateKind == SkeletonRunStateKind.IN_MEMORY) return
    context.phaseGates.readinessGateCoordinator.capturePostValidateFragment(
      ReadinessPostValidateCaptureRequest(
        workflowId = context.request.workflowId,
        repoRoot = context.request.repoRoot,
        baseBranch =
          context.recorder.loadResolvedBranch(context.request.workflowId)?.baseBranch ?: DEFAULT_BASE_BRANCH,
        changedPaths = context.gateChangedPaths(run),
        gitOperations = context.phaseGates.gitOperations,
      ),
    )
  }
}
