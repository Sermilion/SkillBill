package skillbill.engine.goalrunner.execution.core

import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationPendingState
import skillbill.engine.goalrunner.model.GoalRunnerLaunchDiagnostics
import skillbill.engine.goalrunner.model.GoalRunnerLaunchReconciliation
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.planning.model.GoalPlanningSharedContext
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerTelemetryEmitter
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.decomposition.model.DecompositionSubtask

internal data class DriveGoalLoopArgs(
  val initialState: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val telemetryEmitter: GoalRunnerTelemetryEmitter,
  val planning: GoalPlanningSweepOutcome.PreparedAll,
  val pendingState: GoalRunnerIterationPendingState,
)

internal data class BlockedSelectionIterationArgs(
  val state: GoalRunnerManifestState,
  val selection: GoalRunnerSelection.Blocked,
  val request: GoalRunnerRunRequest,
  val attempted: List<Int>,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
)

internal data class SubtaskLaunchRequestArgs(
  val issueKey: String,
  val subtaskId: Int,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: String?,
  val reviewBaseline: GoalSubtaskReviewBaseline?,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class RunSelectedSubtaskArgs(
  val state: GoalRunnerManifestState,
  val selection: GoalRunnerSelection.Run,
  val request: GoalRunnerRunRequest,
  val attemptedSnapshot: () -> List<Int>,
  val recordAttempt: (Int) -> Unit,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val telemetryEmitter: GoalRunnerTelemetryEmitter?,
  val planning: GoalPlanningSweepOutcome.PreparedAll,
)

internal data class DispatchWorkerResultArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val reconciled: GoalRunnerReconciledOutcome,
  val workerRequestResult: GoalRunnerWorkerRequestHandlingResult,
  val launchReconciliation: GoalRunnerLaunchReconciliation,
  val request: GoalRunnerRunRequest,
  val attempted: List<Int>,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val attemptStartMillis: Long?,
)

internal data class RecordPostLaunchStateArgs(
  val refreshed: GoalRunnerManifestState,
  val subtaskId: Int,
  val selection: GoalRunnerSelection.Run,
  val reconciliation: GoalRunnerLaunchReconciliation,
  val request: GoalRunnerRunRequest,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val reAttemptCause: String?,
  val causingLoopEntry: String?,
)

internal data class LaunchSubtaskWithWorkerResultArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: String?,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class LaunchAndReconcileSubtaskArgs(
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val request: GoalRunnerRunRequest,
  val assignedWorkflowId: String?,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val spawnAuthorization: AgentRunSpawnAuthorization?,
)

internal data class RecordStoppedLedgerEntriesArgs(
  val workflowId: String,
  val state: GoalRunnerManifestState,
  val subtaskId: Int,
  val stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
  val reconciled: GoalRunnerReconciledOutcome.Stop,
  val launchDiagnostics: GoalRunnerLaunchDiagnostics?,
  val attemptDurationMillis: Long?,
  val ledger: GoalRunnerLedgerRecorder,
  val request: GoalRunnerRunRequest,
)

internal data class RecordCompletedSubtaskArgs(
  val completed: GoalRunnerManifestState,
  val subtaskId: Int,
  val reconciled: GoalRunnerReconciledOutcome.Complete,
  val request: GoalRunnerRunRequest,
  val observability: GoalRunnerObservabilityEmitter,
  val ledger: GoalRunnerLedgerRecorder,
  val attemptStartMillis: Long?,
)

internal data class StoppedReportArgs(
  val issueKey: String,
  val attempted: List<Int>,
  val subtaskId: Int,
  val reason: GoalRunnerStopReason,
  val blockedReason: String,
  val workflowId: String?,
  val lastResumableStep: String,
)

internal data class ProduceMissingPlansArgs(
  val shared: GoalPlanningSharedContext,
  val request: GoalRunnerRunRequest,
  val identity: GoalPlanningIdentity,
  val provenance: GoalPlanningContractProvenance,
  val sharedCheckpoint: SharedGoalPreplanCheckpoint,
  val activeSubtasks: List<DecompositionSubtask>,
  val startedPlanIds: Set<Int> = emptySet(),
)

internal data class EmptyOrStoppedArgs(
  val outcome: AgentRunLaunchOutcome,
  val shared: GoalPlanningSharedContext,
  val request: GoalRunnerRunRequest,
  val currentSubtaskId: Int,
  val phaseId: String,
  val durationMs: Long,
)
