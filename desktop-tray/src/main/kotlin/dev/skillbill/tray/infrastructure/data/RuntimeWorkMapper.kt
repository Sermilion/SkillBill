package dev.skillbill.tray.infrastructure.data

import dev.skillbill.tray.domain.*
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkDetailItem
import java.time.Instant

internal fun WorkBoardItem.toDomain(): WorkItem = snapshot.toDomain(repositoryRoot, finishedAt, finishedAtSource?.wireValue).copy(
  controlLeaseOwner = controlLease?.ownerToken, controlLeaseGeneration = controlLease?.generation,
  databaseStoreId = observation?.storeId, databaseRevision = observation?.revision,
  liveSection = liveSection?.let { WorkSection.valueOf(it.name) }, controlEligible = controlEligible, requiresAttention = requiresAttention,
  displayGroup = displayGroup?.let { WorkSection.valueOf(it.name) }, displayPriority = displayPriority,
  exactPauseReason = exactPauseReason, databaseFileObservation = databaseFileObservation)

internal fun IdeStatusSnapshot.toDomain(root: String, finished: Instant?, completionSource: String?): WorkItem {
  val scope = execution?.scope
  val id = if (scope == IdeStatusExecutionScope.STANDALONE_PHASE) execution?.invocationId else workflowId ?: execution?.invocationId
  return WorkItem(
    identity = WorkIdentity(requireNotNull(id), repositoryIdentity),
    workflowId = workflowId,
    invocationId = execution?.invocationId,
    repositoryRoot = root,
    branch = branchCorrelation,
    issueKey = issueKey,
    family = workflowFamily?.let { WorkFamily.valueOf(it.name) },
    scope = scope?.let { ExecutionScope.valueOf(it.name) },
    phaseId = execution?.phaseId,
    activity = currentActivity,
    lifecycle = Lifecycle.valueOf(lifecycleState.name),
    freshness = Freshness.valueOf(freshness.name),
    summary = summary,
    currentStepId = currentStep.id,
    currentStepLabel = currentStep.label,
    progressCompleted = progress?.completed,
    progressTotal = progress?.total,
    currentSubtaskId = currentSubtask?.id,
    currentSubtaskStartedAt = currentSubtask?.startedAt,
    currentPhaseId = currentModel?.phaseId,
    phaseExecution = currentPhaseExecution?.let { PhaseExecution(it.phaseId, PhaseExecutionKind.valueOf(it.kind.name), it.count, it.total) },
    currentModel = currentModel?.model,
    currentEffort = currentModel?.effort,
    startedAt = startedAt,
    lastAgentActivityLabel = lastAgentActivityLabel?.wireValue,
    lastAgentActivityAt = lastAgentActivityAt,
    activeDurationMs = activeDurationMs,
    activeDurationAsOf = activeDurationAsOf,
    subtaskActiveDurationMs = currentSubtask?.activeDurationMs,
    subtaskActiveDurationAsOf = currentSubtask?.activeDurationAsOf,
    pauseRequested = pauseRequested == true,
    pauseReasonCode = pauseReason?.code?.wireValue,
    pauseReason = pauseReason?.label,
    planningState = planning?.state?.wireValue,
    plannedSubtasks = planning?.plannedSubtaskCount,
    totalSubtasks = planning?.totalSubtaskCount,
    planningWaveSubtaskIds = planning?.planningWaveSubtaskIds.orEmpty(),
    executionCount = currentPhaseExecution?.count,
    executionTotal = currentPhaseExecution?.total,
    updatedAt = updatedAt,
    finishedAt = finished,
    finishedAtSource = completionSource,
    executionId = execution?.executionId,
    statusStoreId = execution?.statusStoreId,
    runSequence = execution?.runSequence,
    statusRevision = execution?.statusRevision,
  )
}

internal fun WorkDetailItem.toDomain(observedAt: Instant, cursor: String?): Detail {
  val goal = goalStatus
  return Detail(
    item = snapshot.toDomain(repositoryRoot, finishedAt, finishedAtSource?.wireValue).copy(
      databaseStoreId = observation?.storeId, databaseRevision = observation?.revision,
      liveSection = liveSection?.let { WorkSection.valueOf(it.name) }, controlEligible = controlEligible,
      requiresAttention = requiresAttention, displayGroup = displayGroup?.let { WorkSection.valueOf(it.name) },
      displayPriority = displayPriority, exactPauseReason = exactPauseReason,
      databaseFileObservation = databaseFileObservation),
    stepPipeline = stepPipeline,
    goalFinishPipeline = goalFinishPipeline,
    featureTitle = goalManifest?.featureName,
    currentPhase = runtimeStatus?.currentPhase ?: goal?.currentStep,
    completeCount = goal?.completeCount ?: runtimeStatus?.completeCount ?: 0,
    pendingCount = goal?.pendingCount ?: runtimeStatus?.pendingCount ?: 0,
    blockedCount = goal?.blockedCount ?: runtimeStatus?.blockedCount ?: 0,
    activeAgent = goal?.activeAgent,
    executionLiveness = goal?.executionLiveness?.wireValue,
    extras = emptyMap(),
    dependencies = dependencies.map { entry -> SubtaskDependency(entry.subtaskId,
      WorkDependency(entry.dependency.subtaskId, entry.dependency.optional, entry.dependency.skipped)) },
    freshnessDeadline = freshnessDeadline,
    subtasks = goalManifest?.subtasks.orEmpty().map { subtask ->
      WorkSubtask(subtask.id, subtask.name, subtask.status, subtask.commitSha, subtask.blockedReason,
        subtask.dependencies.map { WorkDependency(it.subtaskId, it.optional, it.skipped) })
    },
    phases = runtimeStatus?.phases.orEmpty().map { WorkPhase(it.phaseId, it.status, it.attemptCount, it.finished) },
    attempts = DetailAttempts(goal?.phaseAttemptCounts.orEmpty(), goal?.cumulativeFixIterations.orEmpty(),
      goal?.reAttemptCauseCounts.orEmpty(), goal?.findingsInScope, goal?.supervisorKillCount ?: 0),
    validation = goal?.completedSubtaskValidation.orEmpty().map { validation ->
      SubtaskValidation(validation.subtaskId, validation.validationPassed, validation.integrityProblem,
        validation.evidence?.results.orEmpty().map { ValidationCommand(it.command, it.exitCode) },
        validation.gateExecutionEvidence?.let { evidence ->
          ValidationResult(evidence.validationStatus, evidence.checks, evidence.gateRunCount,
            evidence.gateRuns.map { run -> ValidationGateRun(run.durationMs, run.outcome.wireValue, run.cacheMode.wireValue,
              run.executedWorkUnits, run.executedChecks, run.command, run.exitCode) })
        })
    },
    latestActivity = LatestActivity(
      event = goal?.latestObservabilityEvent?.let { ActivityEvent(it.workflowPhase, it.workerRole, it.activitySummary, it.timestamp) },
      edit = goal?.latestWorktreeEdit?.let { WorktreeEdit(it.recordedAt, it.pathSample, it.netInsertions, it.netDeletions, it.phaseId) },
    ),
    executionHistory = executionHistory.map { ExecutionInstance(it.invocationId, it.executionId, it.lifecycleState,
      it.startedAt, it.updatedAt, it.finishedAt, it.result) },
    observedAt = observedAt,
    nextCursor = cursor,
    pageOffset = pageOffset,
  )
}
