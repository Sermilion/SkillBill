package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.execution.support.CompletedIterationArgs
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationPendingState
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationResult
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationSession
import skillbill.engine.goalrunner.execution.support.LaunchRecordingContext
import skillbill.engine.goalrunner.execution.support.SelectedSubtaskLaunch
import skillbill.engine.goalrunner.execution.support.SelectedSubtaskPreparation
import skillbill.engine.goalrunner.execution.support.StoppedIterationArgs
import skillbill.engine.goalrunner.execution.support.recordLaunchObservabilityAndLedger
import skillbill.engine.goalrunner.launch.GoalRunnerLaunchReconciler
import skillbill.engine.goalrunner.launch.GoalRunnerSubtaskLaunchPrepare
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerLaunchReconciliation
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.telemetry.GoalRunnerTelemetryEmitter
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import java.time.Clock

private sealed interface SubtaskLaunchResult {
  data class Launched(
    val reconciliation: GoalRunnerLaunchReconciliation,
    val workerRequestResult: GoalRunnerWorkerRequestHandlingResult,
  ) : SubtaskLaunchResult

  data object Denied : SubtaskLaunchResult
}

@Inject
class GoalRunnerSelectedSubtaskLoop(
  private val manifestStore: GoalRunnerManifestStore,
  private val subtaskLauncher: GoalRunnerSubtaskLauncher,
  private val reconciler: GoalRunnerLaunchReconciler,
  private val workerRequestHandler: GoalRunnerWorkerRequestHandler,
  private val iterationOutcome: GoalRunnerIterationOutcome,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val launchPrepare: GoalRunnerSubtaskLaunchPrepare,
  private val clock: Clock,
) {
  internal fun runSelectedSubtask(
    args: RunSelectedSubtaskArgs,
    pendingState: GoalRunnerIterationPendingState,
  ): GoalRunnerIterationResult {
    val validationQualityState = pendingState.validationQualityState
    val state = args.state
    val selection = args.selection
    val request = args.request
    val observability = args.observability
    val ledger = args.ledger
    val telemetryEmitter = args.telemetryEmitter
    val planning = args.planning
    val prepared =
      when (val result = prepareSelectedSubtask(state, selection, request, planning)) {
        is SelectedSubtaskPreparation.Stopped -> return result.result
        is SelectedSubtaskPreparation.Ready -> result
      }
    val launch =
      when (
        val result =
          authorizeAndLaunchSelectedSubtask(
            prepared,
            selection,
            request,
            args.recordAttempt,
            telemetryEmitter,
          )
      ) {
        is SelectedSubtaskLaunch.Stopped -> return result.result
        is SelectedSubtaskLaunch.Completed -> result
      }
    val refreshed = launch.workerRequestResult.state
    val reconciled = launch.reconciliation.reconciled
    val reAttemptCause = validationQualityState.takePendingReAttemptCause(prepared.subtaskId)
    val causingLoopEntry = validationQualityState.takePendingCausingLoopEntry(prepared.subtaskId)
    recordPostLaunchState(
      RecordPostLaunchStateArgs(
        refreshed = refreshed,
        subtaskId = prepared.subtaskId,
        selection = selection,
        reconciliation = launch.reconciliation,
        request = request,
        observability = observability,
        ledger = ledger,
        reAttemptCause = reAttemptCause,
        causingLoopEntry = causingLoopEntry,
      ),
    )
    return dispatchWorkerResult(
      DispatchWorkerResultArgs(
        state = refreshed,
        subtaskId = prepared.subtaskId,
        reconciled = reconciled,
        workerRequestResult = launch.workerRequestResult,
        launchReconciliation = launch.reconciliation,
        request = request,
        attempted = args.attemptedSnapshot(),
        observability = observability,
        ledger = ledger,
        attemptStartMillis = launch.attemptStartMillis,
      ),
      pendingState,
    )
  }

  private fun prepareSelectedSubtask(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): SelectedSubtaskPreparation {
    val earlyStop =
      pauseBoundary.pauseBeforeLaunch(state)
        ?: launchPrepare.goalBranchSetupFailure(state, selection, request)
    return earlyStop?.let(SelectedSubtaskPreparation::Stopped)
      ?: prepareSelectedSubtaskState(state, selection.decision.subtask.id, request, planning)
  }

  private fun prepareSelectedSubtaskState(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    planning: GoalPlanningSweepOutcome.PreparedAll,
  ): SelectedSubtaskPreparation {
    val baselineCapture = launchPrepare.goalReviewBaseline(state, subtaskId, request)
    if (baselineCapture.status != WorkflowGitOperationStatus.OK || baselineCapture.baseline == null) {
      return SelectedSubtaskPreparation.Stopped(
        launchPrepare.blockedReviewBaselineIteration(
          state,
          subtaskId,
          "Could not capture the goal-subtask review baseline before implementation. " +
            "Refusing to substitute a branch-wide scope. ${baselineCapture.error}",
          request,
        ),
      )
    }
    val reviewBaseline = requireNotNull(baselineCapture.baseline)
    return runCatching {
      launchPrepare.prepareAttemptedLaunch(state, subtaskId, request, reviewBaseline, planning)
    }.fold(
      onSuccess = { prepared ->
        SelectedSubtaskPreparation.Ready(
          subtaskId = subtaskId,
          attemptedState = prepared.state,
          openWithAssignedId = prepared.openWithAssignedId,
          reviewBaseline = reviewBaseline,
        )
      },
      onFailure = { error ->
        SelectedSubtaskPreparation.Stopped(
          launchPrepare.blockedOnRecoveryError(state, subtaskId, error, request),
        )
      },
    )
  }

  private fun authorizeAndLaunchSelectedSubtask(
    prepared: SelectedSubtaskPreparation.Ready,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    recordAttempt: (Int) -> Unit,
    telemetryEmitter: GoalRunnerTelemetryEmitter?,
  ): SelectedSubtaskLaunch {
    val subtaskId = prepared.subtaskId
    val launchAuthorization =
      manifestStore.authorizeSubtaskLaunch(
        prepared.attemptedState,
        subtaskId,
      )
    if (!launchAuthorization.authorized) {
      return SelectedSubtaskLaunch.Stopped(
        deniedLaunchPause(prepared, launchAuthorization.controlState),
      )
    }
    recordAttempt(subtaskId)
    emitSubtaskStarted(prepared.attemptedState, subtaskId, selection, request, telemetryEmitter)
    val attemptStartMillis = clock.millis()
    val launched =
      when (
        val result =
          launchSubtaskWithWorkerResult(
            LaunchSubtaskWithWorkerResultArgs(
              state = prepared.attemptedState,
              subtaskId = subtaskId,
              request = request,
              assignedWorkflowId = prepared.openWithAssignedId,
              reviewBaseline = prepared.reviewBaseline,
              spawnAuthorization = launchAuthorization.spawnAuthorization,
            ),
          )
      ) {
        is SubtaskLaunchResult.Denied ->
          return SelectedSubtaskLaunch.Stopped(
            deniedLaunchPause(
              prepared,
              manifestStore.controlState(prepared.attemptedState.parentWorkflowId),
            ),
          )
        is SubtaskLaunchResult.Launched -> result
      }
    return SelectedSubtaskLaunch.Completed(
      reconciliation = launched.reconciliation,
      workerRequestResult = launched.workerRequestResult,
      attemptStartMillis = attemptStartMillis,
    )
  }

  private fun deniedLaunchPause(
    prepared: SelectedSubtaskPreparation.Ready,
    controlState: GoalRunnerControlState,
  ): GoalRunnerIterationResult {
    val state =
      prepared.openWithAssignedId?.let { workflowId ->
        manifestStore.deleteIncompatibleChildWorkflow(
          state = prepared.attemptedState,
          subtaskId = prepared.subtaskId,
          workflowId = workflowId,
        )
      } ?: prepared.attemptedState
    return pauseBoundary.pauseBeforeLaunch(state, controlState)
      ?: error(
        "Subtask ${prepared.subtaskId} launch authorization was denied without a durable pause boundary.",
      )
  }

  private fun dispatchWorkerResult(
    args: DispatchWorkerResultArgs,
    pendingState: GoalRunnerIterationPendingState,
  ): GoalRunnerIterationResult {
    val session =
      GoalRunnerIterationSession(
        request = args.request,
        attempted = args.attempted,
        observability = args.observability,
        ledger = args.ledger,
        attemptStartMillis = args.attemptStartMillis,
      )
    val completedSession = session.copy(attempted = emptyList())
    return args.workerRequestResult.operatorConfirmationStop?.let { stop ->
      iterationOutcome.stoppedIteration(
        StoppedIterationArgs(
          state = args.state,
          subtaskId = args.subtaskId,
          reconciled = stop,
          session = session,
        ),
        pendingState,
      )
    } ?: when (val reconciled = args.reconciled) {
      is GoalRunnerReconciledOutcome.Complete ->
        iterationOutcome.completedIteration(
          CompletedIterationArgs(
            state = args.state,
            subtaskId = args.subtaskId,
            reconciled = reconciled,
            session = completedSession,
          ),
        )
      is GoalRunnerReconciledOutcome.Stop ->
        iterationOutcome.stoppedIteration(
          StoppedIterationArgs(
            state = args.state,
            subtaskId = args.subtaskId,
            reconciled = reconciled,
            session = session,
            launchDiagnostics = args.launchReconciliation.diagnostics,
          ),
          pendingState,
        )
    }
  }

  private fun recordPostLaunchState(args: RecordPostLaunchStateArgs) {
    val refreshed = args.refreshed
    val subtaskId = args.subtaskId
    val selection = args.selection
    val reconciliation = args.reconciliation
    val request = args.request
    val observability = args.observability
    val ledger = args.ledger
    val reAttemptCause = args.reAttemptCause
    val causingLoopEntry = args.causingLoopEntry
    refreshed.manifest.workflowIdFor(subtaskId)?.let { workflowId ->
      recordLaunchObservabilityAndLedger(
        LaunchRecordingContext(
          workflowId,
          refreshed,
          subtaskId,
          selection,
          reconciliation,
          reAttemptCause,
          causingLoopEntry,
        ),
        iterationOutcome.safeProgress(workflowId),
        observability,
        ledger,
      )
      launchPrepare.emitGoalReviewSummaries(refreshed.manifest.issueKey, subtaskId, workflowId, request)
    }
  }

  private fun launchSubtaskWithWorkerResult(args: LaunchSubtaskWithWorkerResultArgs): SubtaskLaunchResult {
    val launchReconciliation =
      launchAndReconcileSubtask(
        LaunchAndReconcileSubtaskArgs(
          state = args.state,
          subtaskId = args.subtaskId,
          request = args.request,
          assignedWorkflowId = args.assignedWorkflowId,
          reviewBaseline = args.reviewBaseline,
          spawnAuthorization = args.spawnAuthorization,
        ),
      ) ?: return SubtaskLaunchResult.Denied
    val workerRequestResult =
      workerRequestHandler.handle(
        state = launchReconciliation.refreshed,
        launchOutcome = launchReconciliation.launchOutcome,
        subtaskId = args.subtaskId,
      )
    return SubtaskLaunchResult.Launched(launchReconciliation, workerRequestResult)
  }

  private fun launchAndReconcileSubtask(args: LaunchAndReconcileSubtaskArgs): GoalRunnerLaunchReconciliation? {
    val state = args.state
    val subtaskId = args.subtaskId
    val request = args.request
    val launchOutcome =
      subtaskLauncher.launch(
        reconciler.subtaskLaunchRequest(
          SubtaskLaunchRequestArgs(
            issueKey = state.manifest.issueKey,
            subtaskId = subtaskId,
            request = request,
            assignedWorkflowId = args.assignedWorkflowId,
            reviewBaseline = args.reviewBaseline,
            spawnAuthorization = args.spawnAuthorization,
          ),
        ),
      )
    if (launchOutcome is AgentRunLaunchDenied) return null
    return reconciler.reconcileLaunchOutcome(state, launchOutcome, subtaskId, request)
  }

  private fun emitSubtaskStarted(
    attemptedState: GoalRunnerManifestState,
    subtaskId: Int,
    selection: GoalRunnerSelection.Run,
    request: GoalRunnerRunRequest,
    telemetryEmitter: GoalRunnerTelemetryEmitter?,
  ) {
    telemetryEmitter?.markSubtaskStarted(subtaskId)
    val currentStepId =
      attemptedState.manifest.subtasks
        .firstOrNull { it.id == subtaskId }
        ?.let { subtask ->
          subtask.workflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
            iterationOutcome.safeProgress(workflowId)?.currentStepId
          } ?: subtask.lastResumableStep?.takeIf(String::isNotBlank)
        }
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStarted(
        issueKey = attemptedState.manifest.issueKey,
        subtaskId = subtaskId,
        action = selection.decision.action.name.lowercase(),
        currentStepId = currentStepId,
      ),
    )
  }
}
