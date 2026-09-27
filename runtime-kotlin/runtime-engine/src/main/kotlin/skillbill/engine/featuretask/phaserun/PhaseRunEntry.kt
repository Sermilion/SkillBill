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
  private val runLoopEntry: FeatureTaskRuntimeRunLoopEntry = FeatureTaskRuntimeRunLoopEntry(),
) {
  fun run(request: PhaseRunRequest): PhaseRunResult {
    val definition = SkeletonDefinition.byId(request.definitionId)
    if (definition.runStateKind != SkeletonRunStateKind.IN_MEMORY) {
      throw InMemorySkeletonDefinitionRequiredError(definition.id)
    }
    val facts = InMemoryPhaseRunFacts(request, definition)
    val selection = strategySelectionFacts(facts)
    val progress =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = transitionsFor(facts),
        outputValidator = outputValidator,
        stepVerdictRule = slotStepVerdictRule(strategies, selection, diagnostics),
        resumeRules = strategies.resumeRules(selection),
      )
    val records = InMemoryPhaseRunRecords(clock)
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
        blocked(state, report.completedPhaseIds, report.completedPhaseIds.lastOrNull().orEmpty(), report.reason)
    }

  private fun blocked(
    state: InMemoryPhaseRunState,
    completedStepIds: List<String>,
    stepId: String,
    reason: String,
  ): PhaseRunResult.Blocked =
    PhaseRunResult.Blocked(state.invocationId, completedStepIds, state.reviewResult, stepId, reason)
}

private const val INVOCATION_ID_PREFIX = "phr-"
