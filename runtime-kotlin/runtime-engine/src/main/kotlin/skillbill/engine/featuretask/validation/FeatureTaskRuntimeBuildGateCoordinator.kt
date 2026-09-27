package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateCyclePhase
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateCycleResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.engine.featuretask.validation.model.ValidationGateProgressWrite
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.engine.featuretask.validation.model.ValidationGateTriageResult
import skillbill.engine.featuretask.validation.model.requiresUnparseableGateTriage
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
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.validation.unparseableGateFailureMessage

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
    when (val resolution = resolver.resolve(cycle.changedPaths)) {
      is ValidationGateResolution.Absent -> ValidationGateCycleResult.AbsentFallback
      is ValidationGateResolution.Incompatible -> terminalBlockedResult(resolution.reason)
      is ValidationGateResolution.Declared -> {
        val declaration = resolution.declaration
        if (declaration.buildCommand == null || declaration.cacheBypassingBuildCommand == null) {
          terminalBlockedResult(
            "Pack '${resolution.packSlug}' declares validation_gate but no build_command pair for the build phase.",
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

    val discovery = runGate(cycle, declaration, ValidationGateCyclePhase.INITIAL_DISCOVERY)
    val discoveryFindings = buildFindingsForRepairFromResult(discovery)
    recordGateProgress(
      state = state,
      result = discovery,
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
      return terminalCompletedResult(cycle, measurements)
    }
    val triagePlan = runBuildTriageIfNeeded(cycle, discoveryFindings, persistedPlan = null)
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
    return repairLoop(
      state = state,
      declaration = declaration,
      openFindings = discoveryFindings,
      initialRepairsUsed = 0,
      triagePlan = triagePlan,
    )
  }

  private fun runBuildTriageIfNeeded(
    cycle: ValidationGateCycleRequest,
    findings: List<ValidationGateFinding>,
    persistedPlan: String?,
  ): String? {
    if (!persistedPlan.isNullOrBlank()) return persistedPlan
    if (!requiresUnparseableGateTriage(findings)) return null
    return when (val triage = cycle.agentTriageLauncher.launch(ValidationFindingSetProjection(findings))) {
      is ValidationGateTriageResult.Captured -> triage.validationRepairPlan.takeIf { it.isNotBlank() }
      ValidationGateTriageResult.Empty -> null
    }
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
        return terminalCompletedResult(state.cycle, measurements)
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
    }
  }

  private fun verifyAfterRepair(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
    repairsUsed: Int,
    triagePlan: String?,
  ): List<ValidationGateFinding> {
    val verify = runGate(state.cycle, declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY)
    val verifyFindings = buildFindingsForRepairFromResult(verify)
    recordGateProgress(
      state = state,
      result = verify,
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

  private fun runGate(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    cyclePhase: ValidationGateCyclePhase,
  ): ValidationGateRunResult {
    val packArgv = buildGateArgv(declaration, cyclePhase)
    val cacheMode =
      when (cyclePhase) {
        ValidationGateCyclePhase.INITIAL_DISCOVERY -> ValidationGateCacheMode.CACHE_ELIGIBLE
        ValidationGateCyclePhase.POST_REPAIR_VERIFY -> ValidationGateCacheMode.FORCED_FULL
      }
    val gradleWrapper =
      repoLocalConfig
        .readRepoLocalConfig(ReadRepoLocalConfigRequest(cycle.repoRoot))
        .config
        .validationGate
        .gradleWrapper
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

  private fun recordGateProgress(
    state: BuildGateCycleState,
    result: ValidationGateRunResult,
    write: ValidationGateProgressWrite,
  ) {
    state.measurements +=
      FeatureTaskRuntimeValidationGateRunRecord(
        durationMs = result.durationMs,
        outcome = result.outcome,
        cacheMode = result.cacheMode,
        executedWorkUnits = result.executedWorkUnits,
        executedChecks = result.executedCheckIdentities,
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
                "build_receipt" to buildReceipt,
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

private fun terminalCompletedResult(
  cycle: ValidationGateCycleRequest,
  measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
): ValidationGateCycleResult =
  ValidationGateCycleResult.Terminal(
    ValidationGateCycleTerminalOutcome.Completed(
      output =
        FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
          phaseId = cycle.phaseId,
          repositoryCheckpoint = cycle.repositoryCheckpoint,
          measurements = measurements,
        ),
    ),
  )
