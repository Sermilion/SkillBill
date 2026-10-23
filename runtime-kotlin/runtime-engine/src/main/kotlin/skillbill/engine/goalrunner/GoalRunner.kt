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
import skillbill.engine.goalrunner.model.GoalRunnerBlockRequest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.source.GoalPlanningSource
import skillbill.engine.goalrunner.status.stopped
import skillbill.engine.goalrunner.status.unknownGoal
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerTelemetryEmitter
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Path
import java.time.Clock

@Inject
class GoalRunner(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val goalPlanningSource: GoalPlanningSource,
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
    existingSpecAdmission(trimmed, repoRoot)?.let { return it }
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

  private fun existingSpecAdmission(
    intake: String,
    repoRoot: Path,
  ): GoalIntakeAdmission? =
    intakePreparation.archivedSpec(intake, repoRoot)
      ?: intakePreparation.issueKeyForExistingSpec(intake, repoRoot)?.let(GoalIntakeAdmission::Admitted)

  fun requiresPlanning(request: GoalRunnerRunRequest): Boolean =
    manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot) == null &&
      intakePreparation.prepare(request) == null

  fun run(request: GoalRunnerRunRequest): GoalRunnerRunReport =
    runPreparation.guardEntry({ it is GoalRunnerRunReport.Stopped && it.stop.reason in ACCEPTED_GOAL_BLOCK_REASONS }) {
      runAfterReconciliation(request)
    }

  private fun runAfterReconciliation(request: GoalRunnerRunRequest): GoalRunnerRunReport {
    val durableState =
      manifestStore.loadDurableByIssueKey(request.issueKey, request.repoRoot)?.copy(repoRoot = request.repoRoot)
    val admittedState =
      durableState
        ?: intakePreparation.prepare(request)
        ?: return unknownGoal(request.issueKey)
    alreadyCompleteReport(admittedState)?.let { return it }
    val loadedState =
      when (
        val start = runPreparation.prepareStart(admittedState, request, resumesExistingGoal = durableState != null)
      ) {
        is GoalRunPreparation.PreparationBlocked ->
          return when (
            val ownedStart =
              executionCoordinator.runOwnedWithLease(admittedState.parentWorkflowId) { generation, ownerToken ->
                settleAcceptedGoalBlock(admittedState, start.report, generation, ownerToken)
              }
          ) {
            is GoalRunnerOwnedRun.Completed -> ownedStart.value
            is GoalRunnerOwnedRun.AlreadyRunning -> alreadyRunningReport(admittedState, ownedStart.reason)
          }
        is GoalRunPreparation.Prepared -> start.state
      }
    val childAdmission = runPreparation.existingChildExecutionPlanAdmission(loadedState, request)
    val execute: (Long, String?) -> GoalRunnerRunReport = { generation, ownerToken ->
      val ownedRequest = request.copy(executionGeneration = generation)
      val state = reconcileStateBeforeRun(loadedState)
      val report =
        when (val preparation = runPreparation.prepareRun(state, ownedRequest)) {
          is GoalRunPreparation.PreparationBlocked -> preparation.report
          is GoalRunPreparation.Prepared -> runPrepared(preparation)
        }
      settleAcceptedGoalBlock(loadedState, report, generation, ownerToken)
    }
    val owned =
      if (childAdmission == null) {
        executionCoordinator.runOwnedWithLease(loadedState.parentWorkflowId, execute)
      } else {
        executionCoordinator.runOwnedWithChildAdmissionLease(loadedState.parentWorkflowId, childAdmission, execute)
      }
    return when (owned) {
      is GoalRunnerOwnedRun.Completed -> owned.value
      is GoalRunnerOwnedRun.AlreadyRunning -> alreadyRunningReport(loadedState, owned.reason)
    }
  }

  private fun settleAcceptedGoalBlock(
    state: GoalRunnerManifestState,
    report: GoalRunnerRunReport,
    executionGeneration: Long,
    executionOwnerToken: String?,
  ): GoalRunnerRunReport {
    val attributedReport = report.withParentWorkflowId(state.parentWorkflowId)
    val stop = (attributedReport as? GoalRunnerRunReport.Stopped)?.stop ?: return attributedReport
    if (stop.reason !in ACCEPTED_GOAL_BLOCK_REASONS) return attributedReport
    val acceptedChildReference =
      (stop.acceptedBlockReference ?: stop.historicalBlockReference)?.takeIf {
        it.sourceWorkflowId == stop.workflowId && it.sourceWorkflowId != state.parentWorkflowId
      }
    val childWorkflowId = acceptedChildReference?.sourceWorkflowId
    val acceptedChildBlockReference =
      stop.acceptedBlockReference?.takeIf {
        it.sourceWorkflowId == childWorkflowId
      }
    outcomeStore.markBlocked(
      GoalRunnerBlockRequest(
        workflowId = state.parentWorkflowId,
        blockedReason = stop.blockedReason,
        lastResumableStep = stop.lastResumableStep,
        causingChildWorkflowId = childWorkflowId,
        causingAcceptedBlockReference = acceptedChildBlockReference,
        historicalBlockReference = stop.historicalBlockReference,
        stopReason = stop.reason,
        executionGeneration = executionGeneration,
        executionOwnerToken = executionOwnerToken,
      ),
    )
    return attributedReport
  }

  private fun alreadyCompleteReport(state: GoalRunnerManifestState): GoalRunnerRunReport.AlreadyComplete? {
    val completedAt = state.controlState.goalCompletedAt ?: return null
    if (GoalRunnerPlanner.selectNext(state.manifest) !is GoalRunnerSelection.Done) return null
    return GoalRunnerRunReport.AlreadyComplete(
      issueKey = state.manifest.issueKey,
      completedAt = completedAt,
      pullRequestUrl = state.controlState.goalPullRequestUrl,
      parentWorkflowId = state.parentWorkflowId,
    )
  }

  private companion object {
    val ACCEPTED_GOAL_BLOCK_REASONS =
      setOf(
        GoalRunnerStopReason.BLOCKED,
        GoalRunnerStopReason.POLICY_BLOCKED,
        GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
      )
  }

  private fun recordGoalCompletion(
    parentWorkflowId: String,
    finalReport: GoalRunnerRunReport,
  ) {
    val pullRequestUrl =
      when (finalReport) {
        is GoalRunnerRunReport.Completed -> finalReport.pullRequestUrl?.takeIf(String::isNotBlank)
        is GoalRunnerRunReport.CompletedNoChange -> null
        else -> return
      }
    manifestStore.updateControlState(parentWorkflowId) { current ->
      current.copy(
        goalCompletedAt = clock.instant().toString(),
        goalPullRequestUrl = pullRequestUrl,
      )
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
    ).withParentWorkflowId(loadedState.parentWorkflowId)

  private fun reconcileStateBeforeRun(state: GoalRunnerManifestState): GoalRunnerManifestState {
    val reconciled =
      reconcileGoalManifest(
        manifest = state.manifest,
        authoritativeOutcomes = outcomeStore.authoritativeOutcomes(state.manifest.issueKey, state.parentWorkflowId),
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
    val telemetryEmitter = GoalRunnerTelemetryEmitter(telemetry, clock, state)
    return runCatching {
      telemetryEmitter.goalStarted()
      pauseBoundary.pauseBeforeLaunch(state)?.let { paused ->
        val pausedReport = requireNotNull(paused.report)
        closeGoalTelemetrySegment(telemetryEmitter, state, pausedReport, emptyList())
        return@runCatching pausedReport
      }
      val sweepOutcome =
        if (GoalRunnerPlanner.selectNext(state.manifest) is GoalRunnerSelection.Done) {
          GoalPlanningSweepOutcome.PreparedAll()
        } else {
          goalPlanningSource.prepare(state, effectiveRequest)
        }
      if (sweepOutcome is GoalPlanningSweepOutcome.Stopped) {
        return@runCatching planningStoppedReport(effectiveRequest, state, telemetryEmitter, emptyList(), sweepOutcome)
      }
      val prepared = sweepOutcome as GoalPlanningSweepOutcome.PreparedAll
      state = prepared.state ?: state
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
            planning = prepared,
            pendingState = pendingState,
          ),
        )
      state = loopResult.state
      val finalReport = requireNotNull(loopResult.report)
      closeGoalTelemetrySegment(telemetryEmitter, state, finalReport, loopResult.attempted)
      recordGoalCompletion(state.parentWorkflowId, finalReport)
      emitCompletedGoalEvent(effectiveRequest, finalReport)
      finalReport.withParentWorkflowId(state.parentWorkflowId)
    }.getOrElse { failure ->
      if (failure is InterruptedException) Thread.currentThread().interrupt()
      runCatching { telemetryEmitter.goalFailed() }
        .onFailure { cleanupFailure ->
          if (cleanupFailure is InterruptedException) Thread.currentThread().interrupt()
          if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
      throw failure
    }
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
        parentWorkflowId = state.parentWorkflowId,
        workflowId = null,
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
    manifestStore.updateControlState(state.parentWorkflowId) { current ->
      current.copy(
        goalBlockedReason = (finalReport as? GoalRunnerRunReport.Stopped)?.stop?.blockedReason,
      )
    }
    telemetryEmitter.let { emitter ->
      emitter.emitNewlyTerminalSubtasks(state.manifest, attempted)
      emitter.goalFinished(state.manifest, finalReport)
      if (finalReport is GoalRunnerRunReport.Completed || finalReport is GoalRunnerRunReport.CompletedNoChange) {
        emitter.goalIssueFinished(state.manifest, finalReport)
      }
    }
  }
}

private fun GoalRunnerRunReport.withParentWorkflowId(parentWorkflowId: String): GoalRunnerRunReport =
  when (this) {
    is GoalRunnerRunReport.Completed -> copy(parentWorkflowId = parentWorkflowId)
    is GoalRunnerRunReport.CompletedNoChange -> copy(parentWorkflowId = parentWorkflowId)
    is GoalRunnerRunReport.Stopped -> copy(parentWorkflowId = parentWorkflowId)
    is GoalRunnerRunReport.AlreadyComplete -> copy(parentWorkflowId = parentWorkflowId)
  }
