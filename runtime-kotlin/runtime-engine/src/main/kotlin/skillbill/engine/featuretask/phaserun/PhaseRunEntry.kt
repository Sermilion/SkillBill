package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.transitionsFor
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.featuretask.InMemorySkeletonDefinitionRequiredError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.phase.task.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.SkeletonRunStateKind
import java.time.Clock
import java.util.UUID

@Inject
class PhaseRunEntry(
  internal val strategies: PhaseStrategyLookup,
  internal val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  internal val phaseGates: FeatureTaskRuntimePhaseGates,
  internal val reviewResultAssembly: ParallelCodeReviewRunnerResultAssembly,
  internal val lifecycleTelemetry: LifecycleTelemetryService,
  internal val diagnostics: RuntimeDiagnostics,
  internal val clock: Clock,
  private val intakeResolver: PhaseRunIntakeResolver,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry = FeatureTaskRuntimeRunLoopEntry(),
) {
  fun run(request: PhaseRunRequest): PhaseRunResult {
    val definition = SkeletonDefinition.byId(request.definitionId)
    if (definition.runStateKind != SkeletonRunStateKind.IN_MEMORY) {
      throw InMemorySkeletonDefinitionRequiredError(definition.id)
    }
    val branch = currentBranch(request)
    val intake = intakeResolver.resolve(definition, request, branch?.branch)
    val facts = InMemoryPhaseRunFacts(request, definition, intake)
    val selection = strategySelectionFacts(facts)
    val progress =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = transitionsFor(facts),
        outputValidator = outputValidator,
        stepVerdictRule = slotStepVerdictRule(strategies, selection, diagnostics),
        resumeRules = strategies.resumeRules(selection),
      )
    val records = InMemoryPhaseRunRecords(clock, branch)
    val state =
      InMemoryPhaseRunState(
        facts = facts,
        progress = progress,
        records = records,
        telemetry = FeatureTaskRuntimeRunObservability(records, facts, diagnostics),
        invocationId = request.reviewInvocation.reviewSessionId ?: "$INVOCATION_ID_PREFIX${UUID.randomUUID()}",
        entry = this,
      )
    val report = runLoopEntry.run(FeatureTaskRuntimeRunLoopContext(facts, state, strategies))
    return resultOf(report, state, records)
  }

  private fun resultOf(
    report: FeatureTaskRuntimeRunReport,
    state: InMemoryPhaseRunState,
    records: InMemoryPhaseRunRecords,
  ): PhaseRunResult =
    when (report) {
      is FeatureTaskRuntimeRunReport.Completed ->
        PhaseRunResult.Completed(
          invocationId = state.invocationId,
          completedStepIds = report.completedPhaseIds,
          reviewResult = state.reviewResult,
          value = report.completedPhaseIds.lastOrNull()?.let { id -> records.loadPhaseRecords("")[id]?.outputArtifact },
        )
      is FeatureTaskRuntimeRunReport.Blocked ->
        blocked(state, report.completedPhaseIds, report.lastIncompletePhase, report.blockedReason)
      is FeatureTaskRuntimeRunReport.Paused ->
        blocked(state, report.completedPhaseIds, report.pausedPhase, report.pauseReason)
      is FeatureTaskRuntimeRunReport.Decomposed ->
        PhaseRunResult.Completed(
          invocationId = state.invocationId,
          completedStepIds = report.completedPhaseIds,
          reviewResult = state.reviewResult,
          value = report.reason,
          specBundle =
            PhaseRunSpecBundle(
              parentSpecPath = report.parentSpecPath,
              decompositionManifestPath = report.decompositionManifestPath,
              subtaskSpecPaths = report.subtaskSpecPaths,
            ),
        )
    }

  private fun currentBranch(request: PhaseRunRequest): FeatureTaskRuntimeResolvedBranch? =
    (phaseGates.gitOperations.currentBranch(request.repoRoot) as? WorkflowGitOperationResult.Ok)
      ?.value
      ?.trim()
      ?.takeIf { branch -> branch.isNotBlank() && branch != DETACHED_HEAD }
      ?.let(::FeatureTaskRuntimeResolvedBranch)

  private fun blocked(
    state: InMemoryPhaseRunState,
    completedStepIds: List<String>,
    stepId: String,
    reason: String,
  ): PhaseRunResult.Blocked =
    PhaseRunResult.Blocked(state.invocationId, completedStepIds, state.reviewResult, stepId, reason)
}

private const val INVOCATION_ID_PREFIX = "phr-"
private const val DETACHED_HEAD = "HEAD"
