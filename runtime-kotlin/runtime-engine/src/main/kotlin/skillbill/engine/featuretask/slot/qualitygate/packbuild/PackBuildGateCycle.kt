package skillbill.engine.featuretask.slot.qualitygate.packbuild

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptOnce
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.attempt.recordRejectionAttemptArgs
import skillbill.engine.featuretask.slot.qualitygate.RuntimeOwnedGateSettlement
import skillbill.engine.featuretask.slot.qualitygate.blockGateStep
import skillbill.engine.featuretask.slot.qualitygate.buildGateProgressStore
import skillbill.engine.featuretask.slot.qualitygate.gateChangedPaths
import skillbill.engine.featuretask.slot.qualitygate.gateCheckpoint
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.ports.taskruntime.validateBuildReceipt
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

private const val BUILD_RECEIPT_KEY = "build_receipt"

internal class PackBuildGateCycle(
  private val context: PhaseAttemptEnvironment,
  private val call: PhaseStepCall,
) {
  internal fun run(run: PhaseRun): PhaseOutcome {
    val checkpoint =
      context.gateCheckpoint(run)
        ?: return PhaseOutcome.blocked("Build gate cycle could not resolve a repository checkpoint fingerprint.")
    val iteration = call.state.nextStepIteration()
    persistRunning(run, iteration)?.let { return it }
    val cycle =
      context.phaseGates.buildGateCoordinator.execute(
        cycle = cycleRequest(run, iteration, checkpoint),
        onGateRunCount = { context.observability.validationGateProgress() },
      )
    return settle(run, iteration, checkpoint, cycle)
  }

  private fun persistRunning(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome? {
    val runningPhaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        context.request,
        context.state,
        context.goalContinuationRecorder,
        PhaseStateRequestArgs(
          write =
            PhaseStateWriteArgs(
              run = run,
              iteration = iteration,
              status = STATUS_RUNNING,
              finished = false,
              outputArtifact = null,
            ),
        ),
      )
    context.state.reserveReviewPass(runningPhaseState.reviewPassNumber)
    if (!context.recorder.recordPhaseState(runningPhaseState)) {
      return context.blockGateStep(
        run,
        iteration,
        "Build gate cycle could not persist running build phase before gate execution.",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        context.observability,
      )
    }
    context.observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
    return null
  }

  private fun cycleRequest(
    run: PhaseRun,
    iteration: Int,
    checkpoint: String,
  ): ValidationGateCycleRequest =
    ValidationGateCycleRequest(
      repoRoot = run.request.repoRoot,
      request = run.request,
      phaseId = run.phaseId,
      validationDepth = ValidationDepth.DEFAULT,
      changedPaths = context.gateChangedPaths(run),
      repositoryCheckpoint = checkpoint,
      agentRepairLauncher =
        ValidationGateAgentRepairLauncher { findings, repairTurn, triagePlan ->
          launchRepair(run, iteration, PackBuildRepairTurn(findings, repairTurn, triagePlan))
        },
      progressStore = call.state.records.buildGateProgressStore(),
      agentTriageLauncher = ValidationGateAgentTriageLauncher { findings -> launchTriage(run, iteration, findings) },
    )

  private fun launchTriage(
    run: PhaseRun,
    iteration: Int,
    findings: ValidationFindingSetProjection,
  ): ValidationGateTriageResult {
    val triageRun = run.copy(validationGateFindings = findings, validationGateTriage = true)
    return attemptOnce(triageRun, iteration)?.completedOutput?.let(PackBuildTriagePlan::extract)
      ?: ValidationGateTriageResult.Empty
  }

  private fun attemptOnce(
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome? =
    PhaseAttemptOnce.attemptOnce(
      context,
      recordRejectionAttemptArgs(PhaseAttemptContext(run, context.state, iteration, context.observability), call),
    ).settledOutcome

  private fun launchRepair(
    run: PhaseRun,
    iteration: Int,
    turn: PackBuildRepairTurn,
  ): ValidationGateAgentRepairResult {
    val repairRun =
      run.copy(
        validationGateFindings = turn.findings.takeIf { it.findings.isNotEmpty() },
        validationGateRepairTurn = turn.repairTurn,
        validationGateTriagePlan = turn.triagePlan,
        validationGateRepair = true,
      )
    val settled = attemptOnce(repairRun, iteration)
    val completed = settled?.completedOutput
    return when {
      completed != null -> ValidationGateAgentRepairResult.Completed(completed)
      settled != null ->
        ValidationGateAgentRepairResult.Blocked(
          settled.blockedReason
            ?: settled.pausedReason
            ?: "Validation repair attempt persistence.session.blocked.",
          failureDisposition =
            context.recorder.loadPhaseRecords(run.request.workflowId)
              ?.get(run.phaseId)
              ?.failureDisposition,
        )
      else -> ValidationGateAgentRepairResult.Completed(PackBuildStepHooks.repairSegmentOutput(run, iteration))
    }
  }

  private fun settle(
    run: PhaseRun,
    iteration: Int,
    checkpoint: String,
    cycle: ValidationGateCycleResult,
  ): PhaseOutcome =
    when (cycle) {
      ValidationGateCycleResult.AbsentFallback ->
        runtimeOwnedBuild(
          run,
          iteration,
          FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
            phaseId = run.phaseId,
            repositoryCheckpoint = checkpoint,
            measurements = emptyList(),
          ).payload,
        )
      is ValidationGateCycleResult.Terminal ->
        when (val terminal = cycle.outcome) {
          is ValidationGateCycleTerminalOutcome.Paused -> PhaseOutcome.paused(terminal.reason)
          is ValidationGateCycleTerminalOutcome.Completed -> runtimeOwnedBuild(run, iteration, terminal.output.payload)
          is ValidationGateCycleTerminalOutcome.Blocked ->
            context.blockGateStep(
              run,
              iteration,
              terminal.reason,
              terminal.failureDisposition ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
              context.observability,
            )
        }
    }

  private fun runtimeOwnedBuild(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): PhaseOutcome =
    RuntimeOwnedGateSettlement(context, label = "build", acceptance = ::requireBuildReceipt)
      .settle(run, iteration, outputText, context.observability)

  private fun requireBuildReceipt(
    run: PhaseRun,
    accepted: AcceptedFeatureTaskRuntimePhaseOutput,
  ) {
    val buildReceipt =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.anyToStringAnyMap(
          accepted.normalizedOutput.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS],
        )?.get(BUILD_RECEIPT_KEY),
      )
    context.phaseGates.buildReceiptValidator.validateBuildReceipt(
      buildReceipt ?: emptyMap<String, Any?>(),
      sourceLabel = run.phaseId,
    )
  }
}

private data class PackBuildRepairTurn(
  val findings: ValidationFindingSetProjection,
  val repairTurn: Int,
  val triagePlan: String?,
)
