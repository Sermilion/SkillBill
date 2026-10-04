package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.execution.core.GoalRunnerWorkerRequestHandlingResult
import skillbill.engine.goalrunner.model.GoalRunnerLaunchDiagnostics
import skillbill.engine.goalrunner.model.GoalRunnerLaunchReconciliation
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerContext
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySubject
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerSubtaskAction
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline

internal data class GoalRunnerIterationPendingState(
  val validationQualityState: GoalRunnerValidationQualityPendingState,
)

internal class GoalRunnerAttemptState {
  private val attemptedSubtaskIds = mutableListOf<Int>()

  val attempted: List<Int>
    get() = attemptedSubtaskIds.toList()

  fun record(subtaskId: Int) {
    attemptedSubtaskIds += subtaskId
  }
}

internal data class GoalRunnerIterationSession(
  val request: GoalRunnerRunRequest,
  val attempted: List<Int>,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val attemptStartMillis: Long? = null,
)

internal data class StoppedIterationArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val reconciled: GoalRunnerReconciledOutcome.Stop,
  val session: GoalRunnerIterationSession,
  val launchDiagnostics: GoalRunnerLaunchDiagnostics? = null,
)

internal data class CompletedIterationArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val reconciled: GoalRunnerReconciledOutcome.Complete,
  val session: GoalRunnerIterationSession,
)

internal data class GoalRunnerIterationResult(
  val state: GoalRunnerManifestState,
  val report: GoalRunnerRunReport? = null,
  val attempted: List<Int> = emptyList(),
)

internal data class PreparedLaunch(
  val state: GoalRunnerManifestState,
  val openWithAssignedId: String?,
)

internal sealed interface SelectedSubtaskPreparation {
  class Ready(
    val subtaskId: Int,
    val attemptedState: GoalRunnerManifestState,
    val openWithAssignedId: String?,
    val reviewBaseline: GoalSubtaskReviewBaseline,
  ) : SelectedSubtaskPreparation

  class Stopped(val result: GoalRunnerIterationResult) : SelectedSubtaskPreparation
}

internal sealed interface SelectedSubtaskLaunch {
  class Completed(
    val reconciliation: GoalRunnerLaunchReconciliation,
    val workerRequestResult: GoalRunnerWorkerRequestHandlingResult,
    val attemptStartMillis: Long,
  ) : SelectedSubtaskLaunch

  class Stopped(val result: GoalRunnerIterationResult) : SelectedSubtaskLaunch
}

internal data class LaunchRecordingContext(
  val workflowId: String,
  val refreshed: GoalRunnerManifestState,
  val subtaskId: Int,
  val selection: GoalRunnerSelection.Run,
  val launchReconciliation: GoalRunnerLaunchReconciliation,
  val reAttemptCause: String? = null,
  val causingLoopEntry: String? = null,
)

internal fun recordLaunchObservabilityAndLedger(
  context: LaunchRecordingContext,
  progress: GoalRunnerWorkflowProgress?,
  observability: GoalRunnerObservabilityEmitter,
  ledger: GoalRunnerLedgerRecorder,
) {
  val workflowId = context.workflowId
  val subtaskId = context.subtaskId
  val launchOutcome = context.launchReconciliation.launchOutcome
  observability.recordLaunchLifecycle(
    subject = GoalRunnerObservabilitySubject(workflowId, context.refreshed.manifest.issueKey, subtaskId),
    action = context.selection.decision.action.name.lowercase(),
    progress = progress,
    launchOutcome = launchOutcome,
  )
  ledger.recordLedgerEntry(
    if (context.selection.decision.action == GoalRunnerSubtaskAction.RESUME) {
      GoalRunnerLedgerContext.Resume(
        workflowId = workflowId,
        issueKey = context.refreshed.manifest.issueKey,
        subtaskId = subtaskId,
        progress = progress,
        launchOutcome = launchOutcome,
        diagnosticClass =
          (launchOutcome as? AgentRunLaunchFacts)?.takeIf {
            it.termination != AgentRunTermination.Exited(0)
          }?.let { "child_process_failed" },
        recoverableJsonPresent = null,
        nextSafeAction = "read_terminal_workflow_state",
        reAttemptCause = context.reAttemptCause,
        causingLoopEntry = context.causingLoopEntry,
      )
    } else {
      GoalRunnerLedgerContext.ChildActivation(
        workflowId = workflowId,
        issueKey = context.refreshed.manifest.issueKey,
        subtaskId = subtaskId,
        progress = progress,
        launchOutcome = launchOutcome,
        diagnosticClass =
          (launchOutcome as? AgentRunLaunchFacts)?.takeIf {
            it.termination != AgentRunTermination.Exited(0)
          }?.let { "child_process_failed" },
        recoverableJsonPresent = null,
        nextSafeAction = "read_terminal_workflow_state",
        reAttemptCause = context.reAttemptCause,
        causingLoopEntry = context.causingLoopEntry,
      )
    },
  )
}

internal fun GoalRunnerRunRequest.emitStoppedSubtaskEvent(
  issueKey: String,
  subtaskId: Int,
  stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
) {
  eventSink.emit(
    GoalRunnerRunEvent.SubtaskStopped(
      issueKey = issueKey,
      subtaskId = subtaskId,
      reason = stoppedOutcome.reason.name.lowercase(),
      blockedReason = stoppedOutcome.blockedReason,
      currentStepId = stoppedOutcome.lastResumableStep.takeIf(String::isNotBlank),
    ),
  )
}

internal data class StoppedIterationResultArgs(
  val saved: GoalRunnerManifestState,
  val attempted: List<Int>,
  val subtaskId: Int,
  val stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
  val knownWorkflowId: String?,
  val request: GoalRunnerRunRequest,
)
