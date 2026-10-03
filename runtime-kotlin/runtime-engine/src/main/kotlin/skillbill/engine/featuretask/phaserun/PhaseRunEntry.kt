package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.parallel.runner.ParallelCodeReviewRunnerResultAssembly
import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.slotStepVerdictRule
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.featuretask.InMemorySkeletonDefinitionRequiredError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonRunStateKind
import java.time.Clock
import java.util.UUID

@Inject
class PhaseRunEntry(
  private val strategies: PhaseStrategyLookup,
  private val gitOperations: WorkflowGitOperations,
  private val reviewResultAssembly: ParallelCodeReviewRunnerResultAssembly,
  private val lifecycleTelemetry: LifecycleTelemetryService,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val intakeResolver: PhaseRunIntakeResolver,
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry,
) {
  fun run(request: PhaseRunRequest): PhaseRunResult {
    val definition = SkeletonDefinition.byId(request.definitionId)
    if (definition.runStateKind != SkeletonRunStateKind.IN_MEMORY) {
      throw InMemorySkeletonDefinitionRequiredError(definition.id)
    }
    val branch = currentBranch(request)
    val intake = intakeResolver.resolve(definition, request, branch?.branch)
    val facts = InMemoryPhaseRunFacts(request, definition, intake)
    val executionPlan = strategies.executionPlan(strategySelectionFacts(facts))
    val progress =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = executionPlan.traversal,
        stepVerdictRule = slotStepVerdictRule(strategies, executionPlan, diagnostics),
        resumeRulesFn = strategies.resumeRules(executionPlan),
      )
    val records = InMemoryPhaseRunRecords(clock, branch)
    val state =
      InMemoryPhaseRunState(
        facts = facts,
        executionPlan = executionPlan,
        progress = progress,
        records = records,
        telemetry = FeatureTaskRuntimeRunObservability(records, facts, diagnostics),
        invocationId = request.reviewInvocation.reviewSessionId ?: "$INVOCATION_ID_PREFIX${UUID.randomUUID()}",
        strategies = strategies,
        reviewResultAssembly = reviewResultAssembly,
        lifecycleTelemetry = lifecycleTelemetry,
        clock = clock,
        runLoopEntry = runLoopEntry,
      )
    val report = runLoopEntry.run(runLoopEntry.context(facts, state))
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
    (gitOperations.currentBranch(request.repoRoot) as? WorkflowGitOperationResult.Ok)
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
