package skillbill.engine.goalrunner.model

import skillbill.goalrunner.ledger.summary
import skillbill.goalrunner.model.GoalAttemptLaunchOutcome
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalObservabilityProgressEvent
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerProgressEvent
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.nio.file.Path
import java.time.Instant

public data class GoalRunnerManifestState(
  public val parentWorkflowId: String,
  public val dbPath: String,
  public val manifest: DecompositionManifest,
  public val controlState: GoalRunnerControlState = GoalRunnerControlState(),
  public val repoRoot: Path? = null,
)

public data class GoalRunnerCompletionPersistenceResult(
  public val state: GoalRunnerManifestState,
  public val paused: Boolean,
)

public data class GoalRunnerScopedReplanWriteResult(
  public val state: GoalRunnerManifestState,
  internal val deletedPlanCount: Int,
  internal val plannedSubtaskIdsBefore: List<Int>,
  internal val plannedSubtaskIdsAfter: List<Int>,
  public val sharedPreplanPrepared: Boolean,
  internal val sharedPreplanPreparedBefore: Boolean = sharedPreplanPrepared,
  public val discardedSharedPreplan: Boolean = false,
  public val cascadedPlanSubtaskIds: List<Int> = emptyList(),
  public val clearedChildSubtaskIds: List<Int> = emptyList(),
)

public data class GoalRunnerScopedReplanOptions(
  public val repositoryIdentity: String,
)

public data class GoalRunnerPausePersistenceResult(
  public val parentWorkflowId: String,
  public val controlState: GoalRunnerControlState,
)

public data class GoalRunnerLaunchAuthorization(
  public val authorized: Boolean,
  public val controlState: GoalRunnerControlState,
  public val spawnAuthorization: AgentRunSpawnAuthorization? = null,
)

public data class GoalRunnerReconcileGate(
  internal val allowInactiveReconciliation: Boolean = true,
  internal val requireStalenessEvidence: Boolean = false,
)

public data class GoalRunnerWorkflowProgress(
  public val workflowId: String,
  public val workflowStatus: WorkflowStatus,
  public val currentStepId: String,
  public val progressToken: String,
  internal val latestDurableProgressEvent: GoalRunnerProgressEvent? = null,
  internal val latestGoalObservabilityEvent: GoalObservabilityProgressEvent? = null,
  internal val latestDeclaredProgressEvent: GoalProgressEvent? = null,
  public val latestLivenessSignal: String? = null,
  internal val lastSnapshotUpdatedAt: String? = null,
  public val currentStepEnteredAt: Instant? = null,
) {
  internal constructor(
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

  internal fun resolvedLivenessSignal(stepId: String = currentStepId): String =
    latestGoalObservabilityEvent?.toObservabilityEvent()?.compactLivenessSummary()
      ?: latestDurableProgressEvent?.summary()
      ?: "workflow_status=$workflowStatus; step=$stepId"
}

public data class GoalProgressEventDraft(
  public val eventKind: GoalProgressEventKind,
  public val workflowId: String,
  public val workflowPhase: String,
  public val processAlive: Boolean,
  public val timestamp: Instant,
  public val stepId: String? = null,
  public val operationName: String? = null,
  public val operationKind: String? = null,
  public val expectedLong: Boolean = false,
  public val outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  public val agentId: String? = null,
  public val launchedModel: String? = null,
  public val effort: String? = null,
) {
  internal fun toEvent(sequenceNumber: Int): GoalProgressEvent =
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
      agentId = agentId,
      launchedModel = launchedModel,
      effort = effort,
    )
}

public data class GoalAttemptLedgerEntryDraft(
  public val action: GoalAttemptLedgerAction,
  public val timestamp: Instant,
  public val issueKey: String? = null,
  public val subtaskId: Int? = null,
  public val previousWorkflowId: String? = null,
  public val previousStatus: WorkflowStatus? = null,
  public val previousStep: String? = null,
  public val blockedReason: String? = null,
  public val latestLiveness: String? = null,
  public val launchOutcome: GoalAttemptLaunchOutcome? = null,
  public val timedOut: Boolean? = null,
  public val interrupted: Boolean? = null,
  public val childSessionPath: String? = null,
  public val childSessionId: String? = null,
  public val finalReconciledResult: String? = null,
  public val stopReason: String? = null,
  public val diagnosticClass: String? = null,
  public val currentStep: String? = null,
  public val exitStatus: Int? = null,
  public val recoverableJsonPresent: Boolean? = null,
  public val nextSafeAction: String? = null,
  public val loopId: String? = null,
  public val cumulativeLoopCount: Int? = null,
  public val attemptDurationMillis: Long? = null,
  public val causingLoopEntry: String? = null,
  public val reAttemptCause: String? = null,
  public val findingsInScope: Int? = null,
) {
  public fun toEntry(sequenceNumber: Int): GoalAttemptLedgerEntry =
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

public data class GoalRunnerProgressEventRecordRequest(
  internal val workflowId: String,
  internal val issueKey: String,
  internal val draft: GoalProgressEventDraft,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

public data class GoalRunnerAttemptLedgerRecordRequest(
  internal val workflowId: String,
  internal val issueKey: String,
  internal val draft: GoalAttemptLedgerEntryDraft,
) {
  init {
    require(workflowId.isNotBlank()) { "workflowId is required." }
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

public data class GoalRunnerLedgerSequenceWatermarks(
  internal val backwardEdgeCounts: Map<String, Int> = emptyMap(),
)
