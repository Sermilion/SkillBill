package skillbill.engine.goalrunner.model

import skillbill.goalrunner.model.GoalAttemptLaunchOutcome
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerProgressEvent
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.nio.file.Path
import java.time.Instant

data class GoalRunnerManifestState(
  val parentWorkflowId: String,
  val dbPath: String,
  val manifest: DecompositionManifest,
  val controlState: GoalRunnerControlState = GoalRunnerControlState(),
  val repoRoot: Path? = null,
)

data class GoalRunnerCompletionPersistenceResult(
  val state: GoalRunnerManifestState,
  val paused: Boolean,
)

data class GoalRunnerScopedReplanWriteResult(
  val state: GoalRunnerManifestState,
  val deletedPlanCount: Int,
  val plannedSubtaskIdsBefore: List<Int>,
  val plannedSubtaskIdsAfter: List<Int>,
  val sharedPreplanPrepared: Boolean,
  val sharedPreplanPreparedBefore: Boolean = sharedPreplanPrepared,
  val discardedSharedPreplan: Boolean = false,
  val cascadedPlanSubtaskIds: List<Int> = emptyList(),
  val clearedChildSubtaskIds: List<Int> = emptyList(),
)

data class GoalRunnerScopedReplanOptions(
  val includeSharedPreplan: Boolean = false,
  val expectedSharedPayloadSha256: String? = null,
  val planningIdentity: GoalPlanningIdentity? = null,
)

data class GoalRunnerPausePersistenceResult(
  val parentWorkflowId: String,
  val controlState: GoalRunnerControlState,
)

data class GoalRunnerLaunchAuthorization(
  val authorized: Boolean,
  val controlState: GoalRunnerControlState,
  val spawnAuthorization: AgentRunSpawnAuthorization? = null,
)

data class GoalRunnerReconcileGate(
  val allowInactiveReconciliation: Boolean = true,
  val requireStalenessEvidence: Boolean = false,
)

data class GoalRunnerWorkflowProgress(
  val workflowId: String,
  val workflowStatus: WorkflowStatus,
  val currentStepId: String,
  val progressToken: String,
  val latestDurableProgressEvent: GoalRunnerProgressEvent? = null,
  val latestGoalObservabilityEvent: GoalObservabilityProgressEvent? = null,
  val latestDeclaredProgressEvent: GoalProgressEvent? = null,
  val latestLivenessSignal: String? = null,
  val lastSnapshotUpdatedAt: String? = null,
) {
  constructor(
    workflowId: String,
    workflowStatus: String,
    currentStepId: String,
    progressToken: String,
    latestDurableProgressEvent: GoalRunnerProgressEvent? = null,
    latestGoalObservabilityEvent: GoalObservabilityProgressEvent? = null,
    latestDeclaredProgressEvent: GoalProgressEvent? = null,
    latestLivenessSignal: String? = null,
    lastSnapshotUpdatedAt: String? = null,
  ) : this(
    workflowId = workflowId,
    workflowStatus =
      requireNotNull(WorkflowStatus.fromWire(workflowStatus)) {
        "Unknown workflow status '$workflowStatus'."
      },
    currentStepId = currentStepId,
    progressToken = progressToken,
    latestDurableProgressEvent = latestDurableProgressEvent,
    latestGoalObservabilityEvent = latestGoalObservabilityEvent,
    latestDeclaredProgressEvent = latestDeclaredProgressEvent,
    latestLivenessSignal = latestLivenessSignal,
    lastSnapshotUpdatedAt = lastSnapshotUpdatedAt,
  )
}

data class GoalProgressEventDraft(
  val eventKind: GoalProgressEventKind,
  val workflowId: String,
  val workflowPhase: String,
  val processAlive: Boolean,
  val timestamp: Instant,
  val stepId: String? = null,
  val operationName: String? = null,
  val operationKind: String? = null,
  val expectedLong: Boolean = false,
  val outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
) {
  fun toEvent(sequenceNumber: Int): GoalProgressEvent =
    GoalProgressEvent(
      eventKind = eventKind,
      workflowId = workflowId,
      workflowPhase = workflowPhase,
      processAlive = processAlive,
      sequenceNumber = sequenceNumber,
      timestamp = timestamp,
      stepId = stepId,
      operationName = operationName,
      operationKind = operationKind,
      expectedLong = expectedLong,
      outcome = outcome,
    )
}

data class GoalAttemptLedgerEntryDraft(
  val action: GoalAttemptLedgerAction,
  val timestamp: Instant,
  val issueKey: String? = null,
  val subtaskId: Int? = null,
  val previousWorkflowId: String? = null,
  val previousStatus: WorkflowStatus? = null,
  val previousStep: String? = null,
  val blockedReason: String? = null,
  val latestLiveness: String? = null,
  val launchOutcome: GoalAttemptLaunchOutcome? = null,
  val timedOut: Boolean? = null,
  val interrupted: Boolean? = null,
  val childSessionPath: String? = null,
  val childSessionId: String? = null,
  val finalReconciledResult: String? = null,
  val stopReason: String? = null,
  val diagnosticClass: String? = null,
  val currentStep: String? = null,
  val exitStatus: Int? = null,
  val recoverableJsonPresent: Boolean? = null,
  val nextSafeAction: String? = null,
  val loopId: String? = null,
  val cumulativeLoopCount: Int? = null,
  val attemptDurationMillis: Long? = null,
  val causingLoopEntry: String? = null,
  val reAttemptCause: String? = null,
  val findingsInScope: Int? = null,
) {
  fun toEntry(sequenceNumber: Int): GoalAttemptLedgerEntry =
    GoalAttemptLedgerEntry(
      action = action,
      sequenceNumber = sequenceNumber,
      timestamp = timestamp,
      issueKey = issueKey,
      subtaskId = subtaskId,
      previousWorkflowId = previousWorkflowId,
      previousStatus = previousStatus,
      previousStep = previousStep,
      blockedReason = blockedReason,
      latestLiveness = latestLiveness,
      launchOutcome = launchOutcome,
      timedOut = timedOut,
      interrupted = interrupted,
      childSessionPath = childSessionPath,
      childSessionId = childSessionId,
      finalReconciledResult = finalReconciledResult,
      stopReason = stopReason,
      diagnosticClass = diagnosticClass,
      currentStep = currentStep,
      exitStatus = exitStatus,
      recoverableJsonPresent = recoverableJsonPresent,
      nextSafeAction = nextSafeAction,
      loopId = loopId,
      cumulativeLoopCount = cumulativeLoopCount,
      attemptDurationMillis = attemptDurationMillis,
      causingLoopEntry = causingLoopEntry,
      reAttemptCause = reAttemptCause,
      findingsInScope = findingsInScope,
    )
}

data class GoalRunnerProgressEventRecordRequest(
  val workflowId: String,
  val issueKey: String,
  val draft: GoalProgressEventDraft,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

data class GoalRunnerAttemptLedgerRecordRequest(
  val workflowId: String,
  val issueKey: String,
  val draft: GoalAttemptLedgerEntryDraft,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

data class GoalRunnerLedgerSequenceWatermarks(
  val backwardEdgeCounts: Map<String, Int> = emptyMap(),
)
