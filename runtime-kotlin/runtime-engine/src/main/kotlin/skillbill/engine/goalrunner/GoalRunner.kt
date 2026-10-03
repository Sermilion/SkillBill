package skillbill.engine.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.telemetry.lifecycle.GoalLifecycleTelemetryEmitter
import skillbill.engine.goalrunner.execution.core.DriveGoalLoopArgs
import skillbill.engine.goalrunner.execution.core.GoalRunnerExecutionCoordinator
import skillbill.engine.goalrunner.execution.core.GoalRunnerOwnedRun
import skillbill.engine.goalrunner.execution.core.GoalRunnerPauseBoundary
import skillbill.engine.goalrunner.execution.core.GoalRunnerPerRunLoopAssembler
import skillbill.engine.goalrunner.execution.core.GoalRunnerRunPreparation
import skillbill.engine.goalrunner.execution.core.StoppedReportArgs
import skillbill.engine.goalrunner.execution.core.workflowIdFor
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationPendingState
import skillbill.engine.goalrunner.execution.support.GoalRunnerValidationQualityPendingState
import skillbill.engine.goalrunner.intake.GoalIntake
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.manifest.reconcileGoalManifest
import skillbill.engine.goalrunner.model.GoalIntakeAdmission
import skillbill.engine.goalrunner.model.GoalIntakeMissingInput
import skillbill.engine.goalrunner.model.GoalRunPreparation
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweep
import skillbill.engine.goalrunner.status.stopped
import skillbill.engine.goalrunner.status.unknownGoal
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerTelemetryEmitter
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Path
import java.time.Clock

@Inject
class GoalRunner(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val goalPlanningSweep: GoalPlanningSweep,
  private val telemetry: GoalLifecycleTelemetryEmitter,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val executionCoordinator: GoalRunnerExecutionCoordinator,
  private val runPreparation: GoalRunnerRunPreparation,
  private val perRunLoopAssembler: GoalRunnerPerRunLoopAssembler,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val intakePreparation: GoalIntakePreparation,
) {
  fun admitIntake(
    intake: String,
    repoRoot: Path,
  ): GoalIntakeAdmission {
    val trimmed = intake.trim()
    intakePreparation.issueKeyForExistingSpec(trimmed, repoRoot)?.let { return GoalIntakeAdmission.Admitted(it) }
    if (trimmed.isNotBlank() && trimmed.none(Char::isWhitespace) && !trimmed.contains('/')) {
      manifestStore.readByIssueKeyIfPresent(trimmed, repoRoot)?.let {
        return GoalIntakeAdmission.Admitted(it.manifest.issueKey)
      }
    }
    val parsed =
      GoalIntake.parseOrNull(trimmed)
        ?: return GoalIntakeAdmission.NeedsInput(GoalIntakeMissingInput.ISSUE_KEY, issueKey = null)
    val missing =
      if (manifestStore.readByIssueKeyIfPresent(parsed.issueKey, repoRoot) == null) {
        intakePreparation.missingNewWorkInput(parsed, repoRoot)
      } else {
        null
      }
    return missing?.let { GoalIntakeAdmission.NeedsInput(it, parsed.issueKey) }
      ?: GoalIntakeAdmission.Admitted(parsed.issueKey)
  }

  fun run(request: GoalRunnerRunRequest): GoalRunnerRunReport {
    val admittedState =
      manifestStore.loadDurableByIssueKey(request.issueKey)?.copy(repoRoot = request.repoRoot)
        ?: intakePreparation.prepare(request)
        ?: return unknownGoal(request.issueKey)
    runPreparation.admitPlanningMigration(admittedState, request)
    val migratedState =
      manifestStore.loadDurableByIssueKey(request.issueKey)?.copy(repoRoot = request.repoRoot)
        ?: admittedState
    val loadedState = runPreparation.refreshSpecPlanning(migratedState, request)
    val childAdmission = runPreparation.existingChildExecutionPlanAdmission(loadedState, request)
    val execute = {
      val state = reconcileStateBeforeRun(loadedState)
      when (val preparation = runPreparation.prepareRun(state, request)) {
        is GoalRunPreparation.PreparationBlocked -> preparation.report
        is GoalRunPreparation.Prepared -> runPrepared(preparation)
      }
    }
    val owned =
      if (childAdmission == null) {
        executionCoordinator.runOwned(loadedState.parentWorkflowId, execute)
      } else {
        executionCoordinator.runOwnedWithChildAdmission(loadedState.parentWorkflowId, childAdmission, execute)
      }
    return when (owned) {
      is GoalRunnerOwnedRun.Completed -> owned.value
      is GoalRunnerOwnedRun.AlreadyRunning -> alreadyRunningReport(loadedState, owned.reason)
    }
  }

  private fun alreadyRunningReport(
    loadedState: GoalRunnerManifestState,
    reason: String,
  ): GoalRunnerRunReport =
    stopped(
      StoppedReportArgs(
        issueKey = loadedState.manifest.issueKey,
        attempted = emptyList(),
        subtaskId = loadedState.manifest.currentSubtaskIntent.subtaskId,
        reason = GoalRunnerStopReason.BLOCKED,
        blockedReason = reason,
        workflowId = loadedState.manifest.workflowIdFor(loadedState.manifest.currentSubtaskIntent.subtaskId),
        lastResumableStep =
          loadedState.manifest.subtasks
            .firstOrNull { it.id == loadedState.manifest.currentSubtaskIntent.subtaskId }
            ?.lastResumableStep
            .orEmpty()
            .ifBlank { "plan" },
      ),
    )

  private fun reconcileStateBeforeRun(state: GoalRunnerManifestState): GoalRunnerManifestState {
    val reconciled =
      reconcileGoalManifest(
        manifest = state.manifest,
        authoritativeOutcomes = outcomeStore.authoritativeOutcomes(state.manifest.issueKey),
        acceptances = manifestStore.outOfBandAcceptances(state.parentWorkflowId),
        outcomeStore = outcomeStore,
      )
    return if (reconciled == state.manifest) {
      state
    } else {
      manifestStore.save(state.copy(manifest = reconciled))
    }
  }

  private fun runPrepared(preparation: GoalRunPreparation.Prepared): GoalRunnerRunReport {
    var state = preparation.state
    val effectiveRequest = preparation.request
    val observability = GoalRunnerObservabilityEmitter(outcomeStore, clock, diagnostics)
    val ledger = GoalRunnerLedgerRecorder(outcomeStore, effectiveRequest, clock, diagnostics)
    effectiveRequest.eventSink.emit(GoalRunnerRunEvent.Started(state.manifest.issueKey))
    val telemetryEmitter =
      GoalRunnerTelemetryEmitter(telemetry, clock, state)
        .also { it.goalStarted() }
    pauseBoundary.pauseBeforeLaunch(state)?.let { paused ->
      val pausedReport = requireNotNull(paused.report)
      closeGoalTelemetrySegment(telemetryEmitter, state, pausedReport, emptyList())
      return pausedReport
    }
    val sweepOutcome = goalPlanningSweep.prepare(state, effectiveRequest)
    if (sweepOutcome is GoalPlanningSweepOutcome.Stopped) {
      return planningStoppedReport(effectiveRequest, state, telemetryEmitter, emptyList(), sweepOutcome)
    }
    val validationQualityState = GoalRunnerValidationQualityPendingState(manifestStore)
    validationQualityState.bind(state.parentWorkflowId)
    val pendingState = GoalRunnerIterationPendingState(validationQualityState)
    val goalLoop = perRunLoopAssembler.assemble()
    val loopResult =
      goalLoop.driveGoalLoop(
        DriveGoalLoopArgs(
          initialState = state,
          request = effectiveRequest,
          observability = observability,
          ledger = ledger,
          telemetryEmitter = telemetryEmitter,
          planning = sweepOutcome as GoalPlanningSweepOutcome.PreparedAll,
          pendingState = pendingState,
        ),
      )
    state = loopResult.state
    val finalReport = requireNotNull(loopResult.report)
    closeGoalTelemetrySegment(telemetryEmitter, state, finalReport, loopResult.attempted)
    emitCompletedGoalEvent(effectiveRequest, finalReport)
    return finalReport.withParentWorkflowId(state.parentWorkflowId)
  }

  private fun planningStoppedReport(
    effectiveRequest: GoalRunnerRunRequest,
    state: GoalRunnerManifestState,
    telemetryEmitter: GoalRunnerTelemetryEmitter,
    attempted: List<Int>,
    sweepOutcome: GoalPlanningSweepOutcome.Stopped,
  ): GoalRunnerRunReport {
    val planningStop =
      stopped(
        StoppedReportArgs(
          issueKey = sweepOutcome.issueKey,
          attempted = emptyList(),
          subtaskId = sweepOutcome.currentSubtaskId,
          reason = sweepOutcome.reason,
          blockedReason = sweepOutcome.blockedReason,
          workflowId = null,
          lastResumableStep = sweepOutcome.lastResumableStep,
        ),
      )
    effectiveRequest.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = sweepOutcome.issueKey,
        subtaskId = sweepOutcome.currentSubtaskId,
        reason = sweepOutcome.reason.name.lowercase(),
        blockedReason = sweepOutcome.blockedReason,
        currentStepId = sweepOutcome.lastResumableStep,
      ),
    )
    closeGoalTelemetrySegment(telemetryEmitter, state, planningStop, attempted)
    return planningStop.withParentWorkflowId(state.parentWorkflowId)
  }

  private fun emitCompletedGoalEvent(
    request: GoalRunnerRunRequest,
    finalReport: GoalRunnerRunReport,
  ) {
    if (finalReport is GoalRunnerRunReport.Completed) {
      request.eventSink.emit(
        GoalRunnerRunEvent.Completed(
          issueKey = finalReport.issueKey,
          completedCount = finalReport.subtasksCompleted,
          pendingCount = finalReport.subtasksPending,
          blockedCount = finalReport.subtasksBlocked,
          pullRequestStatus = finalReport.pullRequestStatus,
          pullRequestUrl = finalReport.pullRequestUrl,
        ),
      )
    }
  }

  private fun closeGoalTelemetrySegment(
    telemetryEmitter: GoalRunnerTelemetryEmitter,
    state: GoalRunnerManifestState,
    finalReport: GoalRunnerRunReport,
    attempted: List<Int>,
  ) {
    telemetryEmitter.let { emitter ->
      emitter.emitNewlyTerminalSubtasks(state.manifest, attempted)
      emitter.goalFinished(state.manifest, finalReport)
      if (finalReport is GoalRunnerRunReport.Completed) {
        emitter.goalIssueFinished(state.manifest, finalReport)
      }
    }
  }
}

private fun GoalRunnerRunReport.withParentWorkflowId(parentWorkflowId: String): GoalRunnerRunReport =
  when (this) {
    is GoalRunnerRunReport.Completed -> copy(parentWorkflowId = parentWorkflowId)
    is GoalRunnerRunReport.Stopped -> copy(parentWorkflowId = parentWorkflowId)
  }
