package skillbill.engine.featuretask.validation

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationGateFindingPayloadKeys
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
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
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeValidationEvidenceSchema
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
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
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationFailureSubject
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateTriageDisposition
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
  val lastAgentUnfixedCriteria: List<String>,
  val acceptedRepairOutputs: MutableList<FeatureTaskRuntimeValidationGateRepairOutput>,
  var terminalOutcome: String? = null,
  var terminalReason: String? = null,
  var terminalFailureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  var terminalFailureCode: String? = null,
  var triageDisposition: FeatureTaskRuntimeValidationGateTriageDisposition =
    FeatureTaskRuntimeValidationGateTriageDisposition.UNKNOWN,
)

@Inject
class FeatureTaskRuntimeBuildGateCoordinator(
  private val resolver: ValidationGateResolver,
  private val runner: ValidationGateRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val diagnostics: RuntimeDiagnostics,
  private val executionPlanResolver: FeatureTaskRuntimeExecutionPlanResolver,
) {
  fun execute(
    cycle: ValidationGateCycleRequest,
    onGateRunCount: (Int) -> Unit = {},
  ): ValidationGateCycleResult {
    val resolution =
      recordedResolution(cycle) ?: cycle.request.admittedExecution?.effectiveInputs?.let { inputs ->
        check(inputs.commandFamily == cycle.commandFamily) { "Gate command family differs from admission." }
        inputs.declaration?.let { ValidationGateResolution.Declared(requireNotNull(inputs.packSlug), it) }
          ?: ValidationGateResolution.Absent(inputs.packSlug)
      } ?: resolver.resolve(cycle.changedPaths)

    return when (resolution) {
      is ValidationGateResolution.Absent ->
        terminalBlockedResult(
          "Required ${cycle.commandFamily.name.lowercase()} gate declaration is absent" +
            (resolution.routedPackSlug?.let { " from dominant pack '${it.take(PACK_LABEL_LIMIT)}'." } ?: "."),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
          failureCode = failureLabel(FeatureTaskRuntimeFailureCode.INVALID_QUALITY_GATE_CONFIGURATION),
        )
      is ValidationGateResolution.Incompatible ->
        terminalBlockedResult(
          resolution.reason.take(GATE_FAILURE_DETAIL_LIMIT),
          failureCode = failureLabel(FeatureTaskRuntimeFailureCode.INVALID_QUALITY_GATE_CONFIGURATION),
        )
      is ValidationGateResolution.Declared -> {
        val declaration = resolution.declaration
        if (requiredCommandsMissing(declaration, cycle.commandFamily)) {
          terminalBlockedResult(
            "Pack '${resolution.packSlug.take(
              PACK_LABEL_LIMIT,
            )}' is missing a required ${cycle.commandFamily.name.lowercase()} command.",
            failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
            failureCode = failureLabel(FeatureTaskRuntimeFailureCode.INVALID_QUALITY_GATE_CONFIGURATION),
          )
        } else {
          checkAndRepair(cycle, declaration, onGateRunCount)
        }
      }
    }
  }

  private fun recordedResolution(cycle: ValidationGateCycleRequest): ValidationGateResolution? {
    return cycle.request.acceptedGoalQualityExecutionPlan?.let { plan ->
      val inputs = executionPlanResolver.resolveRecordedInputs(cycle.repoRoot, plan)
      if (inputs.commandFamily != cycle.commandFamily) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Accepted gate command family changed during goal-quality recovery.",
        )
      }
      inputs.declaration?.let { ValidationGateResolution.Declared(requireNotNull(inputs.packSlug), it) }
        ?: ValidationGateResolution.Absent(inputs.packSlug)
    }
  }

  private fun checkAndRepair(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    onGateRunCount: (Int) -> Unit,
  ): ValidationGateCycleResult {
    val loaded = cycle.progressStore.load(cycle.progressKey)
    if (loaded?.pendingExternalOperation != null) {
      return terminalBlockedResult(
        "The outcome of '${loaded.pendingExternalOperation}' is unknown after interruption at checkpoint " +
          "'${loaded.pendingExternalCheckpoint}'. Inspect the repository and gate evidence, then resume explicitly.",
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      )
    }
    val measurements = loaded?.gateRuns?.filterNot { it.legacyUnverified }?.toMutableList() ?: mutableListOf()
    val terminalProgress = loaded.takeIf { cycle.preserveConsumedRepairTurns }
    val state =
      BuildGateCycleState(
        cycle,
        measurements,
        onGateRunCount,
        loaded?.lastAgentUnfixedCriteria.orEmpty(),
        loaded?.acceptedRepairOutputs?.toMutableList() ?: mutableListOf(),
        terminalProgress?.terminalOutcome,
        terminalProgress?.terminalReason,
        terminalProgress?.terminalFailureDisposition,
        terminalProgress?.terminalFailureCode,
        loaded?.triageDisposition ?: FeatureTaskRuntimeValidationGateTriageDisposition.UNKNOWN,
      )

    if (cycle.preserveConsumedRepairTurns && loaded?.terminalOutcome != null) {
      return retainedTerminalResult(loaded, measurements)
    }

    return resumeFindingsOrCompletion(state, declaration, loaded) ?: discoverFindings(state, declaration)
  }

  private fun retainedTerminalResult(
    loaded: FeatureTaskRuntimeValidationGateProgress,
    measurements: List<FeatureTaskRuntimeValidationGateRunRecord>,
  ): ValidationGateCycleResult {
    return when (loaded.terminalOutcome) {
      "paused" ->
        ValidationGateCycleResult.Terminal(
          ValidationGateCycleTerminalOutcome.Paused(requireNotNull(loaded.terminalReason)),
        )
      "blocked" ->
        terminalBlockedResult(
          requireNotNull(loaded.terminalReason),
          remainingFindings =
            loaded.completeFindings.takeIf { it.isNotEmpty() }?.let(::decodeBuildPersistedFindings)
              ?.let(::ValidationFindingSetProjection),
          measurements = measurements,
          failureDisposition = loaded.terminalFailureDisposition,
          failureCode = loaded.terminalFailureCode,
        )
      else -> terminalBlockedResult("Stored gate terminal disposition is unsupported.")
    }
  }

  private fun resumeFindingsOrCompletion(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
    loaded: FeatureTaskRuntimeValidationGateProgress?,
  ): ValidationGateCycleResult? {
    val cycle = state.cycle
    val measurements = state.measurements
    if (loaded?.repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN) {
      val findings = decodeBuildPersistedFindings(loaded.completeFindings)
      val discoveryTriagePending =
        loaded.gateRunCount == 1 && loaded.repairsUsed == 0 && loaded.acceptedRepairOutputs.isEmpty()
      val triagePlan =
        if (discoveryTriagePending) {
          when (val triage = runBuildTriageIfNeeded(state, findings)) {
            is ValidationGateTriageResult.Stopped -> return ValidationGateCycleResult.Terminal(triage.outcome)
            is ValidationGateTriageResult.Captured -> triage.validationRepairPlan
            ValidationGateTriageResult.Empty -> null
          }
        } else {
          loaded.capturedTriagePlan
        }
      return repairLoop(
        state = state,
        declaration = declaration,
        openFindings = findings,
        initialRepairsUsed =
          if (cycle.preserveConsumedRepairTurns) {
            loaded.repairsUsed
          } else {
            operatorResumeRepairTurns(
              loaded.repairsUsed,
            )
          },
        triagePlan = triagePlan,
      )
    }

    val hasCompletedRun = loaded?.gateRuns?.isNotEmpty() == true && loaded.completeFindings.isEmpty()
    if (hasCompletedRun && loaded.repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE) {
      return completedResult(cycle, declaration, measurements)
    }

    return null
  }

  private fun discoverFindings(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
  ): ValidationGateCycleResult {
    val cycle = state.cycle
    val measurements = state.measurements
    val discoveryCheckpoint =
      cycle.repositoryCheckpointProvider()
        ?: return terminalBlockedResult("Required gate could not resolve the repository checkpoint before discovery.")
    persistProgress(
      state,
      ValidationGateProgressWrite(
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE,
        remainingFindings = null,
        completeFindings = emptyList(),
        repairsUsed = 0,
        capturedTriagePlan = null,
      ),
      pendingExternalOperation = commandIdentity(cycle, declaration, ValidationGateCyclePhase.INITIAL_DISCOVERY),
      pendingExternalCheckpoint = discoveryCheckpoint,
    )
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
          triageDisposition =
            if (discoveryFindings.isEmpty() || !requiresUnparseableGateTriage(discoveryFindings)) {
              FeatureTaskRuntimeValidationGateTriageDisposition.NOT_REQUIRED
            } else {
              FeatureTaskRuntimeValidationGateTriageDisposition.NOT_STARTED
            },
        ),
    )
    if (discoveryFindings.isEmpty()) {
      return completedResult(cycle, declaration, measurements)
    }
    val triage = runBuildTriageIfNeeded(state, discoveryFindings)
    return if (triage is ValidationGateTriageResult.Stopped) {
      ValidationGateCycleResult.Terminal(triage.outcome)
    } else {
      val triagePlan = (triage as? ValidationGateTriageResult.Captured)?.validationRepairPlan
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
    state: BuildGateCycleState,
    findings: List<ValidationGateFinding>,
  ): ValidationGateTriageResult {
    val cycle = state.cycle
    retainedTriageResult(state, findings)?.let { return it }
    val checkpoint =
      cycle.repositoryCheckpointProvider()
        ?: return ValidationGateTriageResult.Stopped(
          ValidationGateCycleTerminalOutcome.Blocked(
            "Required gate could not resolve the repository checkpoint before " +
              "triage.",
          ),
        )
    persistProgress(
      state,
      ValidationGateProgressWrite.findingsOpen(
        findings,
        state.cycle.progressStore.load(cycle.progressKey)?.repairsUsed ?: 0,
        null,
        triageDisposition = FeatureTaskRuntimeValidationGateTriageDisposition.NOT_STARTED,
      ),
      pendingExternalOperation = "${cycle.commandFamily.name.lowercase()}:triage:${cycle.phaseId}",
      pendingExternalCheckpoint = checkpoint,
    )
    val result = cycle.agentTriageLauncher.launch(ValidationFindingSetProjection(findings))
    if (result == ValidationGateTriageResult.Empty) {
      persistProgress(
        state,
        ValidationGateProgressWrite.findingsOpen(
          findings,
          state.cycle.progressStore.load(cycle.progressKey)?.repairsUsed ?: 0,
          null,
          triageDisposition = FeatureTaskRuntimeValidationGateTriageDisposition.ACCEPTED_EMPTY,
        ),
      )
    } else if (result is ValidationGateTriageResult.Captured) {
      persistProgress(
        state,
        ValidationGateProgressWrite.findingsOpen(
          findings,
          state.cycle.progressStore.load(cycle.progressKey)?.repairsUsed ?: 0,
          result.validationRepairPlan,
          triageDisposition = FeatureTaskRuntimeValidationGateTriageDisposition.CAPTURED,
        ),
      )
    } else if (result is ValidationGateTriageResult.Stopped) {
      retainTerminalOutcome(state, result.outcome)
    }
    return result
  }

  private fun retainedTriageResult(
    state: BuildGateCycleState,
    findings: List<ValidationGateFinding>,
  ): ValidationGateTriageResult? {
    val cycle = state.cycle
    when (state.triageDisposition) {
      FeatureTaskRuntimeValidationGateTriageDisposition.CAPTURED ->
        return ValidationGateTriageResult.Captured(
          requireNotNull(cycle.progressStore.load(cycle.progressKey)?.capturedTriagePlan),
        )
      FeatureTaskRuntimeValidationGateTriageDisposition.ACCEPTED_EMPTY -> return ValidationGateTriageResult.Empty
      else -> Unit
    }
    if (!requiresUnparseableGateTriage(findings)) {
      if (state.triageDisposition != FeatureTaskRuntimeValidationGateTriageDisposition.NOT_REQUIRED) {
        persistProgress(
          state,
          ValidationGateProgressWrite.findingsOpen(
            findings,
            state.cycle.progressStore.load(state.cycle.progressKey)?.repairsUsed ?: 0,
            null,
            triageDisposition = FeatureTaskRuntimeValidationGateTriageDisposition.NOT_REQUIRED,
          ),
        )
      }
      return ValidationGateTriageResult.Empty
    }
    when (state.triageDisposition) {
      FeatureTaskRuntimeValidationGateTriageDisposition.ACCEPTED_EMPTY,
      FeatureTaskRuntimeValidationGateTriageDisposition.CAPTURED,
      FeatureTaskRuntimeValidationGateTriageDisposition.NOT_STARTED,
      -> Unit
      FeatureTaskRuntimeValidationGateTriageDisposition.NOT_REQUIRED ->
        invalidRetainedTriage("The retained gate findings require triage, but the record says triage was not required.")
      FeatureTaskRuntimeValidationGateTriageDisposition.UNKNOWN ->
        invalidRetainedTriage(
          "The retained gate findings require triage, but the record does not establish whether triage was accepted.",
        )
    }
    return null
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
      exhaustedRepairBudget(state, currentFindings, repairsUsed, triagePlan)?.let { return it }
      persistProgress(
        state = state,
        write =
          ValidationGateProgressWrite.findingsOpen(
            completeFindings = currentFindings,
            repairsUsed = repairsUsed,
            capturedTriagePlan = triagePlan,
          ),
      )
      val turn = launchRepairTurn(state, projection, repairsUsed, triagePlan)
      turn.terminal?.let { return it }
      val repairCheckpoint = requireNotNull(turn.checkpoint)
      val repairResult = turn.result
      repairsUsed++
      val acceptedRepair = requireNotNull((repairResult as? ValidationGateAgentRepairResult.Completed)?.output)
      state.acceptedRepairOutputs += FeatureTaskRuntimeValidationGateRepairOutput(repairsUsed, acceptedRepair.payload)
      currentFindings = verifyAfterRepair(state, declaration, repairsUsed, triagePlan, repairCheckpoint)
        ?: return terminalBlockedResult(
          "Required gate could not resolve the repository checkpoint before verification.",
        )
    }
  }

  private fun launchRepairTurn(
    state: BuildGateCycleState,
    projection: ValidationFindingSetProjection,
    repairsUsed: Int,
    triagePlan: String?,
  ): BuildGateRepairTurn {
    val repairCheckpoint =
      state.cycle.repositoryCheckpointProvider()
        ?: return BuildGateRepairTurn(
          terminal =
            terminalBlockedResult(
              "Required gate could not resolve the repository checkpoint before repair.",
            ),
        )
    val repairIdentity = "${state.cycle.commandFamily.name.lowercase()}:repair:${state.cycle.phaseId}"
    persistProgress(
      state,
      ValidationGateProgressWrite.findingsOpen(projection.findings, repairsUsed, triagePlan),
      pendingExternalOperation = "$repairIdentity:${repairsUsed + 1}",
      pendingExternalCheckpoint = repairCheckpoint,
    )
    val repairResult =
      state.cycle.agentRepairLauncher.launch(
        projection,
        repairsUsed + 1,
        triagePlan,
      )
    val terminal =
      when (repairResult) {
        is ValidationGateAgentRepairResult.Paused ->
          ValidationGateCycleResult.Terminal(
            ValidationGateCycleTerminalOutcome.Paused(repairResult.reason),
          )
        is ValidationGateAgentRepairResult.Blocked ->
          terminalBlockedResult(
            repairResult.reason,
            remainingFindings = projection,
            measurements = state.measurements,
            failureDisposition = repairResult.failureDisposition,
            failureCode = repairResult.failureCode,
          )
        is ValidationGateAgentRepairResult.Completed -> null
      }
    if (terminal is ValidationGateCycleResult.Terminal) {
      retainTerminalOutcome(state, terminal.outcome)
      return BuildGateRepairTurn(terminal = terminal)
    }
    return BuildGateRepairTurn(repairCheckpoint, repairResult)
  }

  private fun exhaustedRepairBudget(
    state: BuildGateCycleState,
    currentFindings: List<ValidationGateFinding>,
    repairsUsed: Int,
    triagePlan: String?,
  ): ValidationGateCycleResult? {
    if (repairsUsed >= MAX_REPAIR_TURNS) {
      persistProgress(
        state = state,
        write =
          ValidationGateProgressWrite.findingsOpen(
            completeFindings = currentFindings,
            repairsUsed = repairsUsed,
            capturedTriagePlan = triagePlan,
            remainingFindings = ValidationFindingSetProjection(currentFindings),
          ),
      )
      return terminalBlockedResult(
        "Build gate still reports ${currentFindings.size} finding(s) after $MAX_REPAIR_TURNS repair " +
          "turns; remaining findings are recorded for the operator.",
        remainingFindings = ValidationFindingSetProjection(currentFindings),
        measurements = state.measurements,
        failureCode = projectFailureCode(currentFindings),
      )
    }
    return null
  }

  private fun projectFailureCode(findings: List<ValidationGateFinding>): String? =
    if (findings.any { it.failureSubject == FeatureTaskRuntimeValidationFailureSubject.PROJECT_SOURCE }) {
      failureLabel(FeatureTaskRuntimeFailureCode.PROJECT_QUALITY_GATE_FAILED)
    } else {
      null
    }

  private fun failureLabel(code: FeatureTaskRuntimeFailureCode): String = "${code::class.simpleName}.${code.name}"

  private fun verifyAfterRepair(
    state: BuildGateCycleState,
    declaration: ValidationGateDeclaration,
    repairsUsed: Int,
    triagePlan: String?,
    repairCheckpoint: String,
  ): List<ValidationGateFinding>? {
    val verificationCheckpoint = state.cycle.repositoryCheckpointProvider()
    if (verificationCheckpoint == null) {
      persistProgress(
        state,
        ValidationGateProgressWrite.findingsOpen(openFindingsFromState(state), repairsUsed, triagePlan),
        pendingExternalOperation = "post_repair_verification_checkpoint",
        pendingExternalCheckpoint = repairCheckpoint,
      )
      return null
    }
    persistProgress(
      state,
      ValidationGateProgressWrite.findingsOpen(openFindingsFromState(state), repairsUsed, triagePlan),
      pendingExternalOperation = commandIdentity(state.cycle, declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY),
      pendingExternalCheckpoint = verificationCheckpoint,
    )
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
    pendingExternalOperation: String? = null,
    pendingExternalCheckpoint: String? = null,
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
        triageDisposition = write.triageDisposition ?: state.triageDisposition,
        lastAgentUnfixedCriteria = state.lastAgentUnfixedCriteria,
        pendingExternalOperation = pendingExternalOperation,
        pendingExternalCheckpoint = pendingExternalCheckpoint,
        acceptedRepairOutputs = state.acceptedRepairOutputs.toList(),
        terminalOutcome = state.terminalOutcome,
        terminalReason = state.terminalReason,
        terminalFailureDisposition = state.terminalFailureDisposition,
        terminalFailureCode = state.terminalFailureCode,
      )
    state.cycle.progressStore.persist(state.cycle.progressKey, progress)
    state.triageDisposition = progress.triageDisposition
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

  private fun retainTerminalOutcome(
    state: BuildGateCycleState,
    outcome: ValidationGateCycleTerminalOutcome,
  ) {
    when (outcome) {
      is ValidationGateCycleTerminalOutcome.Paused -> {
        state.terminalOutcome = "paused"
        state.terminalReason = outcome.reason
      }
      is ValidationGateCycleTerminalOutcome.Blocked -> {
        state.terminalOutcome = "blocked"
        state.terminalReason = outcome.reason
        state.terminalFailureDisposition = outcome.failureDisposition
        state.terminalFailureCode = outcome.failureCode
      }
      is ValidationGateCycleTerminalOutcome.Completed -> return
    }
    val findings =
      when (outcome) {
        is ValidationGateCycleTerminalOutcome.Blocked -> outcome.remainingFindings?.findings.orEmpty()
        else -> openFindingsFromState(state)
      }
    persistProgress(
      state,
      ValidationGateProgressWrite.findingsOpen(
        completeFindings = findings,
        repairsUsed = state.cycle.progressStore.load(state.cycle.progressKey)?.repairsUsed ?: 0,
        capturedTriagePlan = state.cycle.progressStore.load(state.cycle.progressKey)?.capturedTriagePlan,
        remainingFindings = findings.takeIf { it.isNotEmpty() }?.let(::ValidationFindingSetProjection),
      ),
    )
  }

  private fun openFindingsFromState(state: BuildGateCycleState): List<ValidationGateFinding> =
    decodeBuildPersistedFindings(state.cycle.progressStore.load(state.cycle.progressKey)?.completeFindings.orEmpty())

  private fun commandIdentity(
    cycle: ValidationGateCycleRequest,
    declaration: ValidationGateDeclaration,
    phase: ValidationGateCyclePhase,
  ): String =
    "${cycle.commandFamily.name.lowercase()}:${phase.name.lowercase()}:" +
      gateArgv(declaration, cycle.commandFamily, phase).joinToString(" ")

  private fun invalidRetainedTriage(reason: String): Nothing =
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      reason,
    )

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
        throw invalidFeatureTaskRuntimeValidationEvidenceSchema(
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
          failureSubject = FeatureTaskRuntimeValidationFailureSubject.UNKNOWN,
        ),
      )
    }
  }

private fun decodeBuildPersistedFindings(raw: List<Map<String, String?>>): List<ValidationGateFinding> =
  raw.map { map ->
    val failureSubject =
      if (!map.containsKey(ValidationEvidencePayloadKeys.FAILURE_SUBJECT)) {
        FeatureTaskRuntimeValidationFailureSubject.UNKNOWN
      } else {
        val wire =
          map[ValidationEvidencePayloadKeys.FAILURE_SUBJECT]
            ?: throw invalidWorkflowStateSchemaError("Persisted validation finding failure_subject must be a string.")
        FeatureTaskRuntimeValidationFailureSubject.fromWire(wire)
          ?: throw invalidWorkflowStateSchemaError("Persisted validation finding failure_subject is unsupported.")
      }
    ValidationGateFinding(
      module = map[ValidationGateFindingPayloadKeys.MODULE] ?: "",
      ruleOrTestId = map[ValidationGateFindingPayloadKeys.RULE_OR_TEST_ID] ?: "",
      message = map[ValidationGateFindingPayloadKeys.MESSAGE] ?: "",
      location = map[ValidationGateFindingPayloadKeys.LOCATION],
      failureSubject = failureSubject,
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

private data class BuildGateRepairTurn(
  val checkpoint: String? = null,
  val result: ValidationGateAgentRepairResult? = null,
  val terminal: ValidationGateCycleResult? = null,
)
