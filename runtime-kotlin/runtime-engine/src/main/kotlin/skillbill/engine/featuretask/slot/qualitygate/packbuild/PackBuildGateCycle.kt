package skillbill.engine.featuretask.slot.qualitygate.packbuild

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
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
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateCommandFamily
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.model.ValidationGateProgressStore
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.ports.taskruntime.validateBuildReceipt
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

private const val RULE_OR_TEST_ID_KEY = "rule_or_test_id"

internal class PackBuildGateCycle(
  private val context: PhaseAttemptEnvironment,
  private val call: PhaseStepCall,
  private val commandFamily: ValidationGateCommandFamily = ValidationGateCommandFamily.BUILD,
) {
  private var stoppedAttempt: PhaseOutcome? = null

  internal fun run(run: PhaseRun): PhaseOutcome {
    val checkpoint =
      context.gateCheckpoint(run)
        ?: return PhaseOutcome.blocked("Build gate cycle could not resolve a repository checkpoint fingerprint.")
    val iteration = call.state.nextStepIteration()
    persistRunning(run, iteration)?.let { return it }
    var gateRuns = 0
    val changedPaths = context.gateChangedPaths(run)
    val reporting =
      QualityCheckReportingStore(
        run,
        changedPaths,
        call.state.records.buildGateProgressStore(commandFamily),
      )
    val cycle =
      context.phaseGates.buildGateCoordinator.execute(
        cycle = cycleRequest(run, iteration, checkpoint, changedPaths, reporting),
        onGateRunCount = { count ->
          gateRuns = count
          context.observability.validationGateProgress()
        },
      )
    val outcome = stoppedAttempt ?: settle(run, iteration, cycle)
    val finalFindings = reporting.lastFindings
    context.runState.qualityCheckFinished(
        run.phaseId,
        finalFailureCount = if (outcome.completedOutput != null) 0 else finalFindings.size.coerceAtLeast(1),
        failingCheckNames = finalFindings.mapNotNull { it[RULE_OR_TEST_ID_KEY] }.distinct().sorted(),
        iterations = gateRuns,
      )
    return outcome
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
    try {
      context.recorder.recordRequiredPhaseStart(runningPhaseState)
    } catch (rejection: RequiredPhaseWriteRejected) {
      return PhaseAttemptOnce.blockRequiredWriteRejection(context, run, rejection)
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
    changedPaths: List<String>,
    progressStore: ValidationGateProgressStore,
  ): ValidationGateCycleRequest =
    ValidationGateCycleRequest(
      repoRoot = run.request.repoRoot,
      request = run.request,
      phaseId = run.phaseId,
      validationDepth = ValidationDepth.DEFAULT,
      commandFamily = commandFamily,
      changedPaths = changedPaths,
      repositoryCheckpoint = checkpoint,
      repositoryCheckpointProvider = { context.gateCheckpoint(run) },
      agentRepairLauncher =
        ValidationGateAgentRepairLauncher { findings, repairTurn, triagePlan ->
          launchRepair(run, iteration, PackBuildRepairTurn(findings, repairTurn, triagePlan))
        },
      progressStore = progressStore,
      agentTriageLauncher = ValidationGateAgentTriageLauncher { findings -> launchTriage(run, iteration, findings) },
    )

  private inner class QualityCheckReportingStore(
    private val run: PhaseRun,
    private val changedPaths: List<String>,
    private val delegate: ValidationGateProgressStore,
  ) : ValidationGateProgressStore {
    var lastFindings: List<Map<String, String?>> = emptyList()
      private set

    private var reported = false

    override fun persist(
      workflowId: String,
      progress: FeatureTaskRuntimeValidationGateProgress,
    ) {
      delegate.persist(workflowId, progress)
      lastFindings = progress.completeFindings
      if (reported) return
      reported = true
      val resolution = context.phaseGates.validationGateResolver.resolve(changedPaths)
      context.runState.qualityCheckStarted(
        run.phaseId,
        detectedStack = (resolution as? ValidationGateResolution.Declared)?.packSlug.orEmpty(),
        initialFailureCount = progress.completeFindings.size,
      )
    }

    override fun load(workflowId: String): FeatureTaskRuntimeValidationGateProgress? = delegate.load(workflowId)
  }

  private fun launchTriage(
    run: PhaseRun,
    iteration: Int,
    findings: ValidationFindingSetProjection,
  ): ValidationGateTriageResult {
    val triageRun = run.copy(validationGateFindings = findings, validationGateTriage = true)
    val outcome = attemptOnce(triageRun, iteration) ?: return ValidationGateTriageResult.Empty
    outcome.completedOutput?.let { return PackBuildTriagePlan.extract(it) }
    stoppedAttempt = outcome
    return ValidationGateTriageResult.Stopped(
      outcome.pausedReason?.let { ValidationGateCycleTerminalOutcome.Paused(it) }
        ?: ValidationGateCycleTerminalOutcome.Blocked(
          outcome.blockedReason ?: "Gate triage did not complete.",
        ),
    )
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
    if (settled != null && completed == null) stoppedAttempt = settled
    return when {
      completed != null -> ValidationGateAgentRepairResult.Completed(completed)
      settled != null ->
        ValidationGateAgentRepairResult.Blocked(
          settled.blockedReason
            ?: settled.pausedReason
            ?: "Validation repair attempt persistence.session.blocked.",
        )
      else -> ValidationGateAgentRepairResult.Completed(PackBuildStepHooks.repairSegmentOutput(run, iteration))
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
          is ValidationGateCycleTerminalOutcome.Completed -> runtimeOwnedGate(run, iteration, terminal.output.payload)
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

  private fun runtimeOwnedGate(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): PhaseOutcome =
    RuntimeOwnedGateSettlement(
      context,
      label = commandFamily.name.lowercase(),
      acceptance = if (commandFamily == ValidationGateCommandFamily.BUILD) ::requireBuildReceipt else { _, _ -> },
    )
      .settle(run, iteration, outputText, context.observability)

  private fun requireBuildReceipt(
    run: PhaseRun,
    accepted: AcceptedFeatureTaskRuntimePhaseOutput,
  ) {
    val buildReceipt =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.anyToStringAnyMap(
          accepted.normalizedOutput.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS],
        )?.get(ValidationEvidencePayloadKeys.BUILD_RECEIPT),
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
