package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.model.ValidationGateProgressWrite
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.engine.featuretask.validation.model.requiresUnparseableGateTriage
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.validation.unparseableGateFailureMessage

private const val PACK_LABEL_LIMIT = 120
private const val GATE_FAILURE_DETAIL_LIMIT = 512
private const val BUILD_PHASE_STATUS_COMPLETED = "completed"

private data class BuildGateCycleState(
  val cycle: ValidationGateCycleRequest,
  val measurements: MutableList<FeatureTaskRuntimeValidationGateRunRecord>,
  val onGateRunCount: (Int) -> Unit,
)

@Inject
class FeatureTaskRuntimeBuildGateCoordinator(
  private val resolver: ValidationGateResolver,
  private val runner: ValidationGateRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun execute(
    cycle: ValidationGateCycleRequest,
    onGateRunCount: (Int) -> Unit = {},
  ): ValidationGateCycleResult =
    when (
      val resolution =
        cycle.request.admittedExecution?.effectiveInputs?.let { inputs ->
          check(inputs.commandFamily == cycle.commandFamily) { "Gate command family differs from admission." }
          inputs.declaration?.let { ValidationGateResolution.Declared(requireNotNull(inputs.packSlug), it) }
            ?: ValidationGateResolution.Absent(inputs.packSlug)
        } ?: resolver.resolve(cycle.changedPaths)
    ) {
      is ValidationGateResolution.Absent ->
        terminalBlockedResult(
          "Required ${cycle.commandFamily.name.lowercase()} gate declaration is absent" +
            (resolution.routedPackSlug?.let { " from dominant pack '${it.take(PACK_LABEL_LIMIT)}'." } ?: "."),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        )
      is ValidationGateResolution.Incompatible ->
        terminalBlockedResult(
          resolution.reason.take(GATE_FAILURE_DETAIL_LIMIT),
        )
      is ValidationGateResolution.Declared -> {
        val declaration = resolution.declaration
        if (requiredCommandsMissing(declaration, cycle.commandFamily)) {
          terminalBlockedResult(
            "Pack '${resolution.packSlug.take(
              PACK_LABEL_LIMIT,
            )}' is missing a required ${cycle.commandFamily.name.lowercase()} command.",
            failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          )
        } else {
          checkAndRepair(cycle, declaration, onGateRunCount)
        }
      }
    }

  private fun checkAndRepair(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    onGateRunCount: (Int) -> Unit,
  ): ValidationGateCycleResult {
    val loaded = cycle.progressStore.load(cycle.request.workflowId)
    val measurements = loaded?.gateRuns?.toMutableList() ?: mutableListOf()
    val state = BuildGateCycleState(cycle, measurements, onGateRunCount)

    if (loaded?.repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN) {
      return repairLoop(
        state = state,
        declaration = declaration,
        openFindings = decodeBuildPersistedFindings(loaded.completeFindings),
        initialRepairsUsed = operatorResumeRepairTurns(loaded.repairsUsed),
        triagePlan = loaded.capturedTriagePlan,
      )
    }

    val discoveryCheckpoint =
      cycle.repositoryCheckpointProvider()
        ?: return terminalBlockedResult("Required gate could not resolve the repository checkpoint before discovery.")
    val discovery = runGate(cycle, declaration, ValidationGateCyclePhase.INITIAL_DISCOVERY)
    val discoveryFindings = buildFindingsForRepairFromResult(discovery)
    recordGateProgress(
      state = state,
      result = discovery,
      command = discovery.command,
      checkpoint = discoveryCheckpoint,
      write =
        ValidationGateProgressWrite(
          repairWindowPhase = repairWindowPhaseFor(discoveryFindings),
          remainingFindings = null,
          completeFindings = discoveryFindings,
          repairsUsed = 0,
          capturedTriagePlan = null,
        ),
    )
    if (discoveryFindings.isEmpty()) {
      return completedResult(cycle, declaration, measurements)
    }
    val triage = runBuildTriageIfNeeded(cycle, discoveryFindings)
    return if (triage is ValidationGateTriageResult.Stopped) {
      ValidationGateCycleResult.Terminal(triage.outcome)
    } else {
      val triagePlan = (triage as? ValidationGateTriageResult.Captured)?.validationRepairPlan
      if (triagePlan != null) {
        persistProgress(
          state = state,
          write =
            ValidationGateProgressWrite(
              repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN,
              remainingFindings = null,
              completeFindings = discoveryFindings,
              repairsUsed = 0,
              capturedTriagePlan = triagePlan,
            ),
        )
      }
      repairLoop(
        state = state,
        declaration = declaration,
        openFindings = discoveryFindings,
        initialRepairsUsed = 0,
        triagePlan = triagePlan,
      )
    }
  }

  private fun runBuildTriageIfNeeded(
    cycle: ValidationGateCycleRequest,
    findings: List<ValidationGateFinding>,
  ): ValidationGateTriageResult =
    if (requiresUnparseableGateTriage(findings)) {
      cycle.agentTriageLauncher.launch(ValidationFindingSetProjection(findings))
    } else {
      ValidationGateTriageResult.Empty
    }

  private fun repairLoop(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
    openFindings: List<ValidationGateFinding>,
    initialRepairsUsed: Int,
    triagePlan: String?,
  ): ValidationGateCycleResult {
    val measurements = state.measurements
    var repairsUsed = initialRepairsUsed
    var currentFindings = openFindings
    while (true) {
      if (currentFindings.isEmpty()) {
        return completedResult(state.cycle, declaration, measurements)
      }
      val projection = ValidationFindingSetProjection(findings = currentFindings)
      if (repairsUsed >= MAX_REPAIR_TURNS) {
        persistProgress(
          state = state,
          write =
            ValidationGateProgressWrite.findingsOpen(
              completeFindings = currentFindings,
              repairsUsed = repairsUsed,
              capturedTriagePlan = triagePlan,
              remainingFindings = projection,
            ),
        )
        return terminalBlockedResult(
          "Build gate still reports ${currentFindings.size} finding(s) after $MAX_REPAIR_TURNS repair " +
            "turns; remaining findings are recorded for the operator.",
          remainingFindings = projection,
          measurements = measurements,
        )
      }
      persistProgress(
        state = state,
        write =
          ValidationGateProgressWrite.findingsOpen(
            completeFindings = currentFindings,
            repairsUsed = repairsUsed,
            capturedTriagePlan = triagePlan,
          ),
      )
      val terminal =
        when (
          val repair =
            state.cycle.agentRepairLauncher.launch(
              projection,
              repairsUsed + 1,
              triagePlan,
            )
        ) {
          is ValidationGateAgentRepairResult.Paused ->
            ValidationGateCycleResult.Terminal(
              ValidationGateCycleTerminalOutcome.Paused(repair.reason),
            )
          is ValidationGateAgentRepairResult.Blocked ->
            terminalBlockedResult(
              repair.reason,
              remainingFindings = projection,
              measurements = measurements,
              failureDisposition = repair.failureDisposition,
            )
          is ValidationGateAgentRepairResult.Completed -> null
        }
      if (terminal != null) return terminal
      repairsUsed++
      currentFindings = verifyAfterRepair(state, declaration, repairsUsed, triagePlan)
        ?: return terminalBlockedResult(
          "Required gate could not resolve the repository checkpoint before verification.",
        )
    }
  }

  private fun verifyAfterRepair(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
    repairsUsed: Int,
    triagePlan: String?,
  ): List<ValidationGateFinding>? {
    val verificationCheckpoint = state.cycle.repositoryCheckpointProvider() ?: return null
    val verify = runGate(state.cycle, declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY)
    val verifyFindings = buildFindingsForRepairFromResult(verify)
    recordGateProgress(
      state = state,
      result = verify,
      command = verify.command,
      checkpoint = verificationCheckpoint,
      write =
        ValidationGateProgressWrite(
          repairWindowPhase = repairWindowPhaseFor(verifyFindings),
          remainingFindings = null,
          completeFindings = verifyFindings,
          repairsUsed = repairsUsed,
          capturedTriagePlan = triagePlan,
        ),
    )
    return verifyFindings
  }

  private fun repairWindowPhaseFor(
    findings: List<ValidationGateFinding>,
  ): FeatureTaskRuntimeValidationGateRepairWindowPhase =
    if (findings.isEmpty()) {
      FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE
    } else {
      FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN
    }

  private fun wrapperFor(cycle: ValidationGateCycleRequest): String? {
    val admitted = cycle.request.admittedExecution
    return if (admitted != null) {
      admitted.effectiveInputs.gradleWrapper
    } else {
      repoLocalConfig.readRepoLocalConfig(
        ReadRepoLocalConfigRequest(cycle.repoRoot),
      ).config.validationGate.gradleWrapper
    }
  }

  private fun runGate(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    cyclePhase: ValidationGateCyclePhase,
  ): ValidationGateRunResult {
    val packArgv = gateArgv(declaration, cycle.commandFamily, cyclePhase)
    val cacheMode =
      when (cyclePhase) {
        ValidationGateCyclePhase.INITIAL_DISCOVERY -> ValidationGateCacheMode.CACHE_ELIGIBLE
        ValidationGateCyclePhase.POST_REPAIR_VERIFY -> ValidationGateCacheMode.FORCED_FULL
      }
    val gradleWrapper = wrapperFor(cycle)
    return runner.run(
      ValidationGateRunRequest(
        repoRoot = cycle.repoRoot,
        argv = applyValidationGateGradleWrapper(packArgv, gradleWrapper),
        cacheMode = cacheMode,
        declaration = declaration,
        terminalVerifying = cyclePhase == ValidationGateCyclePhase.POST_REPAIR_VERIFY,
        findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
      ),
    )
  }

  private fun completedResult(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
  ): ValidationGateCycleResult {
    val terminal =
      measurements.lastOrNull()
        ?: return terminalBlockedResult("Required gate has no terminal command record.")
    val checkpoint =
      terminal.repositoryCheckpoint
        ?: return terminalBlockedResult("Required gate has no terminal repository checkpoint.")
    if (checkpoint != cycle.repositoryCheckpointProvider()) {
      return terminalBlockedResult("Repository checkpoint changed after the required gate command.")
    }
    val terminalPhase =
      if (terminal.cacheMode == ValidationGateCacheMode.FORCED_FULL) {
        ValidationGateCyclePhase.POST_REPAIR_VERIFY
      } else {
        ValidationGateCyclePhase.INITIAL_DISCOVERY
      }
    val wrapper = wrapperFor(cycle)
    val requiredCommand =
      applyValidationGateGradleWrapper(
        gateArgv(declaration, cycle.commandFamily, terminalPhase),
        wrapper,
      ).joinToString(" ")
    return if (terminal.command != requiredCommand || terminal.exitCode != 0 ||
      terminal.outcome != ValidationGateRunOutcome.PASSED
    ) {
      terminalBlockedResult("Terminal required gate command evidence does not match its configured invocation.")
    } else {
      val output =
        when (cycle.commandFamily) {
          ValidationGateCommandFamily.BUILD ->
            runtimeOwnedBuildOutput(cycle.phaseId, checkpoint, measurements)
          ValidationGateCommandFamily.VALIDATION -> {
            FeatureTaskRuntimeValidationGateCoordinator.runtimeOwnedValidationOutput(
              phaseId = cycle.phaseId,
              repositoryCheckpoint = checkpoint,
              measurements = measurements,
              requiredCommand = requiredCommand,
            )
          }
        }
      ValidationGateCycleResult.Terminal(ValidationGateCycleTerminalOutcome.Completed(output))
    }
  }

  private fun recordGateProgress(
    state: BuildGateCycleState,
    result: ValidationGateRunResult,
    command: String,
    checkpoint: String,
    write: ValidationGateProgressWrite,
  ) {
    state.measurements +=
      FeatureTaskRuntimeValidationGateRunRecord(
        durationMs = result.durationMs,
        outcome = result.outcome,
        cacheMode = result.cacheMode,
        executedWorkUnits = result.executedWorkUnits,
        executedChecks = result.executedCheckIdentities,
        command = command,
        exitCode = result.exitCode,
        repositoryCheckpoint = checkpoint,
        executedChecksRecorded = true,
      )
    persistProgress(state = state, write = write)
  }

  private fun persistProgress(
    state: BuildGateCycleState,
    write: ValidationGateProgressWrite,
  ) {
    val progress =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = state.measurements.size,
        gateRuns = state.measurements.toList(),
        remainingFindings = write.remainingFindings?.toHandoffMaps().orEmpty(),
        completeFindings = ValidationFindingSetProjection(findings = write.completeFindings).toHandoffMaps(),
        repairWindowPhase = write.repairWindowPhase,
        repairsUsed = write.repairsUsed,
        capturedTriagePlan = write.capturedTriagePlan,
      )
    state.cycle.progressStore.persist(state.cycle.request.workflowId, progress)
    emitFeatureTaskRuntimeEventSafely(
      diagnostics = diagnostics,
      seam = "BuildGateProgress event-sink emission",
    ) {
      state.cycle.request.eventSink.emit(
        FeatureTaskRuntimeRunEvent.ValidationGateProgress(
          workflowId = state.cycle.request.workflowId,
          phaseId = state.cycle.phaseId,
          gateRunCount = progress.gateRunCount,
        ),
      )
    }
    state.onGateRunCount(progress.gateRunCount)
  }

  companion object {
    const val MAX_REPAIR_TURNS: Int = 3

    private fun operatorResumeRepairTurns(repairsUsed: Int): Int =
      if (repairsUsed >= MAX_REPAIR_TURNS) 0 else repairsUsed

    fun runtimeOwnedBuildOutput(
      phaseId: String,
      repositoryCheckpoint: String,
      measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
    ): FeatureTaskRuntimePhaseOutput {
      val gateExecutionEvidence = FeatureTaskRuntimeValidationGateExecutionEvidence.fromGateMeasurements(measurements)
      if (measurements.lastOrNull()?.repositoryCheckpoint != repositoryCheckpoint) {
        throw InvalidFeatureTaskRuntimeValidationEvidenceSchemaError(
          phaseId,
          "Build gate terminal checkpoint mismatch.",
        )
      }
      val buildReceipt =
        linkedMapOf<String, Any?>(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION,
        ) + workflowArtifactEntryMap(gateExecutionEvidence.asWorkflowArtifactEntry(repositoryCheckpoint))
      val payload =
        JsonCodec.mapToJsonString(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to phaseId,
            SharedPayloadKeys.STATUS to BUILD_PHASE_STATUS_COMPLETED,
            SharedPayloadKeys.SUMMARY to "Build satisfied by runtime-owned gate execution.",
            SharedPayloadKeys.VERDICT to FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
            SharedPayloadKeys.PRODUCED_OUTPUTS to
              mapOf(
                ValidationEvidencePayloadKeys.BUILD_RECEIPT to buildReceipt,
              ),
          ),
        )
      return FeatureTaskRuntimePhaseOutput(
        phaseId = phaseId,
        iteration = 1,
        payload = payload,
      )
    }
  }
}

private fun buildFindingsForRepairFromResult(result: ValidationGateRunResult): List<ValidationGateFinding> =
  if (result.outcome == ValidationGateRunOutcome.PASSED) {
    emptyList()
  } else {
    result.findings.ifEmpty {
      listOf(
        ValidationGateFinding(
          module = "<build-gate>",
          ruleOrTestId = "unparseable_gate_failure",
          message =
            unparseableGateFailureMessage(
              gateLabel = "Build gate",
              outcome = result.outcome.wireValue,
              exitCode = result.exitCode,
              stdout = result.stdout,
            ),
          location = null,
        ),
      )
    }
  }

private fun decodeBuildPersistedFindings(raw: List<Map<String, String?>>): List<ValidationGateFinding> =
  raw.map { map ->
    ValidationGateFinding(
      module = map["module"] ?: "",
      ruleOrTestId = map["rule_or_test_id"] ?: "",
      message = map["message"] ?: "",
      location = map["location"],
    )
  }

private fun requiredCommandsMissing(
  declaration: ValidationGateDeclaration,
  family: ValidationGateCommandFamily,
): Boolean =
  when (family) {
    ValidationGateCommandFamily.BUILD ->
      declaration.buildCommand.isNullOrEmpty() || declaration.cacheBypassingBuildCommand.isNullOrEmpty()
    ValidationGateCommandFamily.VALIDATION ->
      declaration.collectAllFullGateCommand.isEmpty() || declaration.cacheBypassingCollectAllFullGateCommand.isEmpty()
  }

private fun gateArgv(
  declaration: ValidationGateDeclaration,
  family: ValidationGateCommandFamily,
  phase: ValidationGateCyclePhase,
): List<String> =
  when (family) {
    ValidationGateCommandFamily.BUILD -> buildGateArgv(declaration, phase)
    ValidationGateCommandFamily.VALIDATION -> validationGateArgv(declaration, phase)
  }
