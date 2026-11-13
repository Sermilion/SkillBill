package skillbill.engine.featuretask.validation

import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.projectchecks.ProjectCheckSnapshotParser
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCommandSource
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeProjectCheckBuildCommandAttempt
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.validation.gateStdoutExcerpt
import java.nio.file.Path

internal data class OrderedProjectConfigRun(
  val cycle: ValidationGateCycleRequest,
  val declaration: ValidationGateDeclaration?,
  val commands: List<ProjectCheckCommand>,
  val cyclePhase: ValidationGateCyclePhase,
  val snapshot: ProjectCheckCommandSnapshot,
)

internal object FeatureTaskRuntimeBuildGateProjectConfig {
  fun runSavedCommands(
    runner: ValidationGateRunner,
    repoRoot: Path,
    snapshot: ProjectCheckCommandSnapshot,
    commands: List<ProjectCheckCommand>,
    cyclePhase: ValidationGateCyclePhase,
  ): ValidationGateRunResult {
    val cacheMode = cacheModeFor(cyclePhase)
    val runDeclaration = ProjectCheckSnapshotParser.parserDeclaration(snapshot)
    val listIdentity = FeatureTaskRuntimeBuildGatePolicy.commandListIdentity(commands)
    var merged = emptyMergedResult(cacheMode, listIdentity)
    commands.forEach { command ->
      val result = runner.run(commandRunRequest(repoRoot, command, cacheMode, runDeclaration, cyclePhase))
      merged = mergeResults(merged, result)
      if (result.outcome == ValidationGateRunOutcome.REJECTED_ZERO_WORK) {
        return merged
      }
    }
    return merged
  }

  fun runOrderedCommands(
    runner: ValidationGateRunner,
    request: OrderedProjectConfigRun,
  ): ValidationGateRunResult {
    val cacheMode = cacheModeFor(request.cyclePhase)
    val runDeclaration =
      ProjectCheckSnapshotParser.parserDeclaration(request.snapshot) ?: request.declaration
    val listIdentity = FeatureTaskRuntimeBuildGatePolicy.commandListIdentity(request.commands)
    val attemptEntries = mutableListOf<FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry>()
    var merged = emptyMergedResult(cacheMode, listIdentity)
    request.commands.forEach { command ->
      val result =
        runner.run(
          commandRunRequest(
            request.cycle.repoRoot,
            command,
            cacheMode,
            runDeclaration,
            request.cyclePhase,
          ),
        )
      attemptEntries += completedEntry(command, result)
      persistAttempt(request.cycle, listIdentity, attemptEntries)
      merged = mergeResults(merged, result)
    }
    return merged
  }

  private fun commandRunRequest(
    repoRoot: Path,
    command: ProjectCheckCommand,
    cacheMode: ValidationGateCacheMode,
    declaration: ValidationGateDeclaration?,
    cyclePhase: ValidationGateCyclePhase,
  ): ValidationGateRunRequest =
    ValidationGateRunRequest(
      repoRoot = repoRoot,
      argv = command.argv,
      cacheMode = cacheMode,
      declaration = declaration,
      terminalVerifying = cyclePhase == ValidationGateCyclePhase.POST_REPAIR_VERIFY,
      findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
      commandSource = ValidationGateCommandSource.PROJECT_CONFIG,
      executionWorkingDirectory = command.workingDirectory,
    )

  private fun cacheModeFor(cyclePhase: ValidationGateCyclePhase): ValidationGateCacheMode =
    when (cyclePhase) {
      ValidationGateCyclePhase.INITIAL_DISCOVERY -> ValidationGateCacheMode.CACHE_ELIGIBLE
      ValidationGateCyclePhase.POST_REPAIR_VERIFY -> ValidationGateCacheMode.FORCED_FULL
    }

  private fun emptyMergedResult(
    cacheMode: ValidationGateCacheMode,
    listIdentity: String,
  ): ValidationGateRunResult =
    ValidationGateRunResult(
      exitCode = 0,
      durationMs = 0,
      outcome = ValidationGateRunOutcome.PASSED,
      cacheMode = cacheMode,
      executedWorkUnits = 0,
      executedCheckIdentities = emptyList(),
      findings = emptyList(),
      stdout = "",
      command = listIdentity,
    )

  private fun completedEntry(
    command: ProjectCheckCommand,
    result: ValidationGateRunResult,
  ): FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry =
    FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry(
      argv = command.argv,
      workingDirectory = command.workingDirectory,
      command = result.command,
      exitCode = result.exitCode,
      durationMs = result.durationMs,
      outcome = result.outcome,
      stdoutExcerpt = gateStdoutExcerpt(result.stdout, maxChars = 8192),
    )

  private fun mergeResults(
    prior: ValidationGateRunResult,
    next: ValidationGateRunResult,
  ): ValidationGateRunResult =
    prior.copy(
      exitCode = if (prior.exitCode != 0) prior.exitCode else next.exitCode,
      durationMs = prior.durationMs + next.durationMs,
      outcome = combinedOutcome(prior.outcome, next.outcome),
      executedWorkUnits = prior.executedWorkUnits + next.executedWorkUnits,
      executedCheckIdentities = prior.executedCheckIdentities + next.executedCheckIdentities,
      findings = (prior.findings + next.findings).distinctBy(ValidationGateFinding::identity),
      stdout = gateStdoutExcerpt(prior.stdout + next.stdout, maxChars = 8192).orEmpty(),
    )

  private fun combinedOutcome(
    prior: ValidationGateRunOutcome,
    next: ValidationGateRunOutcome,
  ): ValidationGateRunOutcome =
    when {
      prior == ValidationGateRunOutcome.FAILED || next == ValidationGateRunOutcome.FAILED ->
        ValidationGateRunOutcome.FAILED
      prior == ValidationGateRunOutcome.REJECTED_ZERO_WORK || next == ValidationGateRunOutcome.REJECTED_ZERO_WORK ->
        ValidationGateRunOutcome.REJECTED_ZERO_WORK
      else -> ValidationGateRunOutcome.PASSED
    }

  private fun persistAttempt(
    cycle: ValidationGateCycleRequest,
    listIdentity: String,
    entries: List<FeatureTaskRuntimeProjectCheckBuildCommandAttemptEntry>,
  ) {
    if (entries.isEmpty()) return
    val loaded = cycle.progressStore.load(cycle.progressKey) ?: return
    cycle.progressStore.persist(
      cycle.progressKey,
      loaded.copy(
        buildCommandAttempt = FeatureTaskRuntimeProjectCheckBuildCommandAttempt(listIdentity, entries),
      ),
    )
  }
}
