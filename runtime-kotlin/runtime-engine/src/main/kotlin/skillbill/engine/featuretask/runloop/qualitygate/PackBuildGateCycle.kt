package skillbill.engine.featuretask.runloop.qualitygate

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.attempt.PhaseQualityGateCycleContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepCall
import skillbill.engine.featuretask.slot.attempt.RuntimeOwnedGateSettlement
import skillbill.engine.featuretask.slot.attempt.blockGateStep
import skillbill.engine.featuretask.slot.attempt.gateChangedPaths
import skillbill.engine.featuretask.slot.attempt.gateCheckpoint
import skillbill.engine.featuretask.slot.attempt.persistGateRequiredRunning
import skillbill.engine.featuretask.slot.attempt.runGateAttemptOnce
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.PackBuildTriagePlan
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.model.ValidationGateProgressStore
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.engine.featuretask.validation.repairSegmentOutput
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

private const val RULE_OR_TEST_ID_KEY = "rule_or_test_id"

internal class PackBuildGateCycle(
  private val context: PhaseQualityGateCycleContext,
  private val call: PhaseStepCall,
  private val commandFamily: ValidationGateCommandFamily,
  private val buildCoordinator: FeatureTaskRuntimeBuildGateCoordinator,
  private val receiptValidator: FeatureTaskRuntimeWireArtifactValidator,
) {
  private var stoppedAttempt: PhaseOutcome? = null

  internal fun run(run: PhaseRun): PhaseOutcome {
    call.acceptedExecution.requireAcceptedStep(run, call.strategyId)
    val checkpoint =
      context.gateCheckpoint(run)
        ?: return PhaseOutcome.blocked("Build gate cycle could not resolve a repository checkpoint fingerprint.")
    val iteration = call.acceptedExecution.nextStepIteration()
    persistRunning(run, iteration)?.let { return it }
    var gateRuns = 0
    val changedPaths = context.gateChangedPaths(run)
    val reporting =
      QualityCheckReportingStore(
        run,
        changedPaths,
        context.recorder.buildGateProgressStore(commandFamily),
      )
    val cycle =
      buildCoordinator.execute(
        cycle = cycleRequest(run, iteration, checkpoint, changedPaths, reporting),
        onGateRunCount = { count ->
          gateRuns = count
          context.observability.validationGateProgress()
        },
      )
    val outcome = stoppedAttempt ?: settle(run, iteration, cycle)
    val finalFindings = reporting.lastFindings
    context.qualityCheckFinished(
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
  ): PhaseOutcome? = context.persistGateRequiredRunning(run, iteration)

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
      validationDepth =
        run.request.admittedExecution
          ?.effectiveInputs
          ?.validationDepth ?: ValidationDepth.DEFAULT,
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
      val admitted = run.request.admittedExecution
      val packSlug =
        if (admitted != null) {
          admitted.effectiveInputs.packSlug
        } else {
          (
            context.qualityGateCycles.resolve(
              run.request,
              changedPaths,
            ) as? ValidationGateResolution.Declared
          )?.packSlug
        }
      context.qualityCheckStarted(
        run.phaseId,
        detectedStack = packSlug.orEmpty(),
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
    val outcome = attemptOnce(run, triageRun, iteration) ?: return ValidationGateTriageResult.Empty
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
    acceptedRun: PhaseRun,
    run: PhaseRun,
    iteration: Int,
  ): PhaseOutcome? = context.runGateAttemptOnce(call, acceptedRun, run, iteration)

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
    val settled = attemptOnce(run, repairRun, iteration)
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
      else -> ValidationGateAgentRepairResult.Completed(repairSegmentOutput(run, iteration))
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
    ).settle(run, iteration, outputText, context.observability)

  private fun requireBuildReceipt(
    run: PhaseRun,
    accepted: NormalizedFeatureTaskRuntimePhaseOutput,
  ) {
    val buildReceipt =
      JsonCodec.anyToStringAnyMap(
        JsonCodec
          .anyToStringAnyMap(
            accepted.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS],
          )?.get(ValidationEvidencePayloadKeys.BUILD_RECEIPT),
      )
    receiptValidator.validate(
      FeatureTaskRuntimeWireArtifactKind.BUILD_RECEIPT,
      FeatureTaskRuntimeWorkflowArtifactMap.from(buildReceipt ?: emptyMap<String, Any?>()),
      run.phaseId,
    )
  }
}

private data class PackBuildRepairTurn(
  val findings: ValidationFindingSetProjection,
  val repairTurn: Int,
  val triagePlan: String?,
)
