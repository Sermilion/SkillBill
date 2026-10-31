package skillbill.engine.goalrunner.execution.core

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationPendingState
import skillbill.engine.goalrunner.model.GoalRunnerLaunchDiagnostics
import skillbill.engine.goalrunner.model.GoalRunnerLaunchReconciliation
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerTelemetryEmitter
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline

public data class DriveGoalLoopArgs(
  internal val initialState: GoalRunnerManifestState,
  public val request: GoalRunnerRunRequest,
  public val observability: GoalRunnerObservabilityEmitter,
  public val ledger: GoalRunnerLedgerRecorder,
  internal val telemetryEmitter: GoalRunnerTelemetryEmitter,
  public val planning: GoalPlanningSweepOutcome.PreparedAll,
  internal val pendingState: GoalRunnerIterationPendingState,
)

internal data class BlockedSelectionIterationArgs(
  public val state: GoalRunnerManifestState,
  public val selection: GoalRunnerSelection.Blocked,
  public val request: GoalRunnerRunRequest,
  public val attempted: List<Int>,
  public val observability: GoalRunnerObservabilityEmitter,
  public val ledger: GoalRunnerLedgerRecorder,
)

internal data class SubtaskLaunchRequestArgs(
  public val issueKey: String,
  public val subtaskId: Int,
  public val request: GoalRunnerRunRequest,
  public val assignedWorkflowId: String?,
  public val reviewBaseline: GoalSubtaskReviewBaseline?,
  public val spawnAuthorization: AgentRunSpawnAuthorization?,
)

public data class RunSelectedSubtaskArgs(
  public val state: GoalRunnerManifestState,
  public val selection: GoalRunnerSelection.Run,
  public val request: GoalRunnerRunRequest,
  internal val attemptedSnapshot: () -> List<Int>,
  internal val recordAttempt: (Int) -> Unit,
  public val observability: GoalRunnerObservabilityEmitter,
  public val ledger: GoalRunnerLedgerRecorder,
  internal val telemetryEmitter: GoalRunnerTelemetryEmitter?,
  public val planning: GoalPlanningSweepOutcome.PreparedAll,
)

internal data class DispatchWorkerResultArgs(
  public val state: GoalRunnerManifestState,
  public val subtaskId: Int,
  public val reconciled: GoalRunnerReconciledOutcome,
  internal val workerRequestResult: GoalRunnerWorkerRequestHandlingResult,
  internal val launchReconciliation: GoalRunnerLaunchReconciliation,
  public val request: GoalRunnerRunRequest,
  public val attempted: List<Int>,
  public val observability: GoalRunnerObservabilityEmitter,
  public val ledger: GoalRunnerLedgerRecorder,
  internal val attemptStartMillis: Long?,
)

internal data class RecordPostLaunchStateArgs(
  public val refreshed: GoalRunnerManifestState,
  public val subtaskId: Int,
  public val selection: GoalRunnerSelection.Run,
  public val reconciliation: GoalRunnerLaunchReconciliation,
  public val request: GoalRunnerRunRequest,
  public val observability: GoalRunnerObservabilityEmitter,
  public val ledger: GoalRunnerLedgerRecorder,
  public val reAttemptCause: String?,
  public val causingLoopEntry: String?,
)

internal data class LaunchSubtaskWithWorkerResultArgs(
  public val state: GoalRunnerManifestState,
  public val subtaskId: Int,
  public val request: GoalRunnerRunRequest,
  public val assignedWorkflowId: String?,
  public val reviewBaseline: GoalSubtaskReviewBaseline,
  public val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class LaunchAndReconcileSubtaskArgs(
  public val state: GoalRunnerManifestState,
  public val subtaskId: Int,
  public val request: GoalRunnerRunRequest,
  public val assignedWorkflowId: String?,
  public val reviewBaseline: GoalSubtaskReviewBaseline,
  public val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class RecordStoppedLedgerEntriesArgs(
  public val workflowId: String,
  public val state: GoalRunnerManifestState,
  public val subtaskId: Int,
  internal val stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
  public val reconciled: GoalRunnerReconciledOutcome.Stop,
  internal val launchDiagnostics: GoalRunnerLaunchDiagnostics?,
  public val attemptDurationMillis: Long?,
  public val ledger: GoalRunnerLedgerRecorder,
  public val request: GoalRunnerRunRequest,
)

internal data class RecordCompletedSubtaskArgs(
  public val completed: GoalRunnerManifestState,
  public val subtaskId: Int,
  public val reconciled: GoalRunnerReconciledOutcome.Complete,
  public val request: GoalRunnerRunRequest,
  public val observability: GoalRunnerObservabilityEmitter,
  public val ledger: GoalRunnerLedgerRecorder,
  internal val attemptStartMillis: Long?,
)

internal data class StoppedReportArgs(
  public val issueKey: String,
  public val attempted: List<Int>,
  public val subtaskId: Int,
  public val reason: GoalRunnerStopReason,
  public val blockedReason: String,
  public val workflowId: String?,
  public val lastResumableStep: String,
  public val noChangeReason: String? = null,
  public val incidentToken: String? = null,
  public val acceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
  public val historicalBlockReference: GoalRunnerAcceptedBlockReference? = null,
)
