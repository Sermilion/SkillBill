package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeProbeWriters
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePreparation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.core.FeatureTaskPhaseSettlementService
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunPreparation
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

@Inject
class FeatureTaskRuntimeRunner(
  val strategies: PhaseStrategyLookup,
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  val phaseSettlementService: FeatureTaskPhaseSettlementService,
  val diagnostics: RuntimeDiagnostics,
  val clock: Clock,
  val probeWriters: FeatureTaskRuntimeProbeWriters,
  val runLoopEntry: FeatureTaskRuntimeRunLoopEntry = FeatureTaskRuntimeRunLoopEntry(),
) {
  val activityStampWriter get() = probeWriters.activityStampWriter
  val worktreeEditJournalWriter get() = probeWriters.worktreeEditJournalWriter

  fun run(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport {
    val reconciliation = crashReconciler.reconcile()
    return when (val preparation = prepareRun(request)) {
      is FeatureTaskRuntimePreparation.PreparationBlocked -> preparation.report
      is FeatureTaskRuntimePreparation.Prepared -> executePreparedRun(preparation.request, reconciliation)
    }
  }

  private fun prepareRun(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimePreparation =
    foreignModeWorkflowBlock(request)?.let(FeatureTaskRuntimePreparation::PreparationBlocked)
      ?: FeatureTaskRuntimeRunPreparation(
        recorder,
        goalContinuationRecorder,
        runInvariantsStore,
        strategies,
      ).prepare(request)

  private fun foreignModeWorkflowBlock(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport.Blocked? {
    val existingMode = recorder.existingWorkflowMode(request.workflowId)
    if (existingMode == null || existingMode == FeatureTaskWorkflowMode.RUNTIME) {
      return null
    }
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultInitialStepId,
      blockedReason =
        "Cannot resume workflow '${request.workflowId}' in runtime mode: it was created in " +
          "'${existingMode.wireValue}' mode. A feature-task workflow is mode-scoped — prose and runtime are " +
          "not interchangeable. Finish this subtask in '${existingMode.wireValue}' mode, or reset the subtask " +
          "to start a fresh runtime attempt.",
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )
  }
}
