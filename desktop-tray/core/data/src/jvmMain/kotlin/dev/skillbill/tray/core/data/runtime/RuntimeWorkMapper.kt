package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.domain.model.*
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState as RuntimeAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification as RuntimeAssessmentVerification
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.idestatus.model.ExecutionReasonRestoration
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusWorkflowQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkIncidentProjection
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview
import java.nio.file.Path
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import skillbill.ports.system.CheckedOutBranchSource

internal fun ExecutionReasonRestoration.toDomain(): ReasonRestoration = when (this) {
  ExecutionReasonRestoration.EXACT -> ReasonRestoration.EXACT
  ExecutionReasonRestoration.RESTORED -> ReasonRestoration.RESTORED
  ExecutionReasonRestoration.MISSING -> ReasonRestoration.MISSING
  ExecutionReasonRestoration.AMBIGUOUS -> ReasonRestoration.AMBIGUOUS
  ExecutionReasonRestoration.BUDGET_EXCEEDED -> ReasonRestoration.BUDGET_EXCEEDED
  ExecutionReasonRestoration.MISSING_CONTENT -> ReasonRestoration.MISSING_CONTENT
}

internal fun StandaloneQaOutcome.toDomain(): QaVerdict = when (this) {
  StandaloneQaOutcome.ATTEMPTED -> QaVerdict.ATTEMPTED
  StandaloneQaOutcome.PASSED -> QaVerdict.PASSED
  StandaloneQaOutcome.FINDINGS -> QaVerdict.FINDINGS
  StandaloneQaOutcome.SKIPPED -> QaVerdict.SKIPPED
  StandaloneQaOutcome.INCOMPLETE -> QaVerdict.INCOMPLETE
}

/** An unparseable `recordedAt` drops the QA signal for this item rather than failing the board. */
internal fun IdeStatusWorkflowQaOutcome.toDomain(): WorkQaOutcome? =
  runCatching { Instant.parse(recordedAt) }.getOrNull()?.let { WorkQaOutcome(outcome.wireValue, reason, failureKind, it) }

internal fun StandalonePlanReport.toDomain(): PlanRunReport =
  PlanRunReport(featureName, parentSpecPath, subtasks.map { PlannedSubtask(it.id, it.name, it.specPath, it.dependsOn) }, bundleFiles)

internal fun DatabaseFileObservation.toDomain(): DatabaseFileIdentity = DatabaseFileIdentity(path.toString(), fileKey)

internal fun DatabaseFileIdentity.toObservation(): DatabaseFileObservation = DatabaseFileObservation(Path.of(path), fileKey)

internal fun WorkBoardItem.toDomain(): WorkItem = snapshot.toDomain(repositoryRoot, finishedAt?.toKotlinInstant(), finishedAtSource?.wireValue).copy(
  controlLeaseOwner = controlLease?.ownerToken, controlLeaseGeneration = controlLease?.generation,
  databaseStoreId = observation?.storeId, databaseRevision = observation?.revision,
  liveSection = liveSection?.let { WorkSection.valueOf(it.name) }, controlEligible = controlEligible, pauseEligible = pauseEligible, requiresAttention = requiresAttention,
  displayGroup = displayGroup?.let { WorkSection.valueOf(it.name) }, displayPriority = displayPriority,
  exactPauseReason = exactPauseReason, running = running, recentlyFinishedPhase = recentlyFinishedPhase,
  doneWithinDay = doneWithinDay, membershipDeadline = membershipDeadline?.toKotlinInstant(),
  databaseFile = databaseFileObservation?.toDomain())

internal fun IdeStatusSnapshot.toDomain(root: String, finished: Instant?, completionSource: String?): WorkItem {
  val scope = execution?.scope
  val id = if (scope == IdeStatusExecutionScope.STANDALONE_PHASE) execution?.invocationId else workflowId ?: execution?.invocationId
  return WorkItem(
    identity = WorkIdentity(requireNotNull(id), repositoryIdentity),
    workflowId = workflowId,
    invocationId = execution?.invocationId,
    repositoryRoot = root,
    repositoryName = RepositoryNames.nameOf(root),
    branch = branchCorrelation,
    issueKey = issueKey,
    family = workflowFamily?.let { WorkFamily.valueOf(it.name) },
    scope = scope?.let { ExecutionScope.valueOf(it.name) },
    goalLaunched = execution?.launchScope == IdeStatusLaunchScope.GOAL,
    qaVerdict = execution?.qaOutcome?.toDomain(),
    qaOutcome = workflowQaOutcome?.toDomain(),
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
    currentSubtaskStartedAt = currentSubtask?.startedAt?.toKotlinInstant(),
    currentPhaseId = currentModel?.phaseId,
    phaseExecution = currentPhaseExecution?.let { PhaseExecution(it.phaseId, PhaseExecutionKind.valueOf(it.kind.name), it.count, it.total, it.criterionRef) },
    currentModel = currentModel?.model,
    currentEffort = currentModel?.effort,
    startedAt = startedAt?.toKotlinInstant(),
    lastAgentActivityLabel = lastAgentActivityLabel?.wireValue,
    lastAgentActivityAt = lastAgentActivityAt?.toKotlinInstant(),
    stalledSince = stalledSince?.toKotlinInstant(),
    activeDurationMs = activeDurationMs,
    activeDurationAsOf = activeDurationAsOf?.toKotlinInstant(),
    subtaskActiveDurationMs = currentSubtask?.activeDurationMs,
    subtaskActiveDurationAsOf = currentSubtask?.activeDurationAsOf?.toKotlinInstant(),
    pauseRequested = pauseRequested == true,
    pauseReasonCode = pauseReason?.code?.wireValue,
    pauseReason = pauseReason?.label,
    planningState = planning?.state?.wireValue,
    plannedSubtasks = planning?.plannedSubtaskCount,
    totalSubtasks = planning?.totalSubtaskCount,
    planningWaveSubtaskIds = planning?.planningWaveSubtaskIds.orEmpty(),
    executionCount = currentPhaseExecution?.count,
    executionTotal = currentPhaseExecution?.total,
    updatedAt = updatedAt.toKotlinInstant(),
    finishedAt = finished,
    finishedAtSource = completionSource,
    executionId = execution?.executionId,
    statusStoreId = execution?.statusStoreId,
    runSequence = execution?.runSequence,
    statusRevision = execution?.statusRevision,
  )
}

internal fun WorkItem.withCheckedOutBranch(branches: CheckedOutBranchSource): WorkItem =
  if (scope == ExecutionScope.STANDALONE_PHASE) this
  else branches.checkedOutBranch(Path.of(repositoryRoot))?.let { copy(branch = it) } ?: this

internal fun WorkDetailItem.toDomain(observedAt: Instant, cursor: String?): Detail {
  val goal = goalStatus
  return Detail(
    item = snapshot.toDomain(repositoryRoot, finishedAt?.toKotlinInstant(), finishedAtSource?.wireValue).copy(
      databaseStoreId = observation?.storeId, databaseRevision = observation?.revision,
      liveSection = liveSection?.let { WorkSection.valueOf(it.name) }, controlEligible = controlEligible,
      pauseEligible = pauseEligible, requiresAttention = requiresAttention, displayGroup = displayGroup?.let { WorkSection.valueOf(it.name) },
      displayPriority = displayPriority, exactPauseReason = exactPauseReason,
      controlLeaseOwner = controlLease?.ownerToken, controlLeaseGeneration = controlLease?.generation,
      databaseFile = databaseFileObservation?.toDomain()),
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
    freshnessDeadline = freshnessDeadline?.toKotlinInstant(),
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
    phaseResults = goal?.completedSubtaskPhaseResults.orEmpty().map { result ->
      SubtaskPhaseResults(result.subtaskId,
        result.reviewPasses.map { pass -> ReviewPass(pass.passNumber, pass.verdict, pass.unresolvedFindingCount,
          pass.findings.map { ReviewFinding(it.severity, it.label, it.text) }) },
        result.audit?.let { PhaseConclusion(it.verdict, it.summary, it.detail, it.attemptCount) },
        result.review?.let { PhaseConclusion(it.verdict, it.summary, it.detail, it.attemptCount) })
    },
    goalQa = goal?.goalQaResult?.let { qa ->
      GoalQaResult(
        qa.outcome, qa.reason, qa.qaFixAttempts, qa.recordedAt.toKotlinInstant(),
        qa.findings.map { GoalQaFinding(it.id, it.title, it.evidencePath, it.fixed) },
        qa.reportText,
        qa.failureKind,
      )
    },
    goalAssessment = goal?.goalAssessmentResult?.let { assessment ->
      GoalAssessmentResult(
        assessment.state.toDomain(), assessment.round, assessment.exitReason?.wireValue, assessment.fixRan,
        assessment.findings.map {
          GoalAssessmentFinding(it.ref, it.severity.wireValue, it.path, it.line, it.failureScenario,
            it.verification.toDomain(), it.state.toDomain(), it.evidence)
        },
      )
    },
    latestActivity = LatestActivity(
      event = goal?.latestObservabilityEvent?.let { ActivityEvent(it.workflowPhase, it.workerRole, it.activitySummary, it.timestamp?.toKotlinInstant()) },
      edit = goal?.latestWorktreeEdit?.let { WorktreeEdit(it.recordedAt?.toKotlinInstant(), it.pathSample, it.netInsertions, it.netDeletions, it.phaseId) },
    ),
    executionHistory = executionHistory.map { ExecutionInstance(it.invocationId, it.executionId, it.lifecycleState,
      it.startedAt.toKotlinInstant(), it.updatedAt.toKotlinInstant(), it.finishedAt?.toKotlinInstant(), it.result,
      it.restoration?.toDomain(), it.qaOutcome?.toDomain(), it.qaReportText, it.planReport?.toDomain()) },
    observedAt = observedAt,
    nextCursor = cursor,
    pageOffset = pageOffset,
    incident = incident?.toDomain(),
    incidentHistory = IncidentHistory(incidentHistory.map(WorkIncidentProjection::toDomain)),
    goalSpecBundle = goalSpecBundle?.toDomain(),
  )
}

// Exhaustive on purpose: a runtime constant without a desktop counterpart must fail the build, not detail load.
private fun GoalRunnerAssessmentState.toDomain(): GoalAssessmentState = when (this) {
  GoalRunnerAssessmentState.PENDING -> GoalAssessmentState.PENDING
  GoalRunnerAssessmentState.RUNNING -> GoalAssessmentState.RUNNING
  GoalRunnerAssessmentState.COMPLETED -> GoalAssessmentState.COMPLETED
  GoalRunnerAssessmentState.SKIPPED -> GoalAssessmentState.SKIPPED
}

private fun RuntimeAssessmentVerification.toDomain(): GoalAssessmentVerification = when (this) {
  RuntimeAssessmentVerification.CONFIRMED -> GoalAssessmentVerification.CONFIRMED
  RuntimeAssessmentVerification.REFUTED -> GoalAssessmentVerification.REFUTED
  RuntimeAssessmentVerification.UNVERIFIED -> GoalAssessmentVerification.UNVERIFIED
  RuntimeAssessmentVerification.NOT_REQUIRED -> GoalAssessmentVerification.NOT_REQUIRED
}

private fun RuntimeAssessmentFindingState.toDomain(): GoalAssessmentFindingState = when (this) {
  RuntimeAssessmentFindingState.OPEN -> GoalAssessmentFindingState.OPEN
  RuntimeAssessmentFindingState.FIXED -> GoalAssessmentFindingState.FIXED
  RuntimeAssessmentFindingState.REFUTED -> GoalAssessmentFindingState.REFUTED
}

private fun WorkIncidentProjection.toDomain(): Incident = Incident(
  incidentToken, revision, IncidentProgress.valueOf(progress.name), summary, cause, certainty.wireValue,
  responsibleCategory.wireValue, nextAction, noWorkaroundKnown,
  prerequisites.map { IncidentPrerequisite(it.kind, it.instruction) },
  evidence.map { IncidentEvidence(it.kind.wireValue, it.referenceId, it.summary, it.trusted) },
  degradations.map { it.wireValue }.toSet(),
  IncidentRecovery(
    when (val value = recovery.eligibility) {
      is BlockIncidentRecoveryEligibilityResult.Available ->
        IncidentRecoveryEligibility.Available(value.preview.toDomain())
      is BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites -> IncidentRecoveryEligibility.NeedsPrerequisites(
        value.instructions.map { IncidentPrerequisite(it.kind.wireValue, it.instruction) },
      )
      is BlockIncidentRecoveryEligibilityResult.Unavailable ->
        IncidentRecoveryEligibility.Unavailable(IncidentRecoveryUnavailableReason.valueOf(value.reason.name))
      null -> null
    },
    recovery.previewLabel, recovery.effects, recovery.targetWorkflowId, recovery.targetInvocationId,
    recovery.latestAttemptId, recovery.latestAttemptStatus?.wireValue, recovery.failure?.wireValue,
  ),
  IncidentReport(report.status.wireValue, report.failureReason?.wireValue, report.issueUrl),
  IncidentTelemetry(
    telemetry.availability,
    mapOfNotNullValues(
      "install_id" to telemetry.installId,
      "incident_token" to telemetry.incidentToken,
      "workflow_id" to telemetry.workflowId,
      "invocation_id" to telemetry.invocationId,
    ),
  ),
  observedAt.toKotlinInstant(), settledAt?.toKotlinInstant(),
)

private fun BlockIncidentRecoveryPreview.toDomain() = IncidentRecoveryPreview(
  action, label, effects, targetWorkflowId, targetInvocationId, phaseId, incidentToken, incidentRevision,
)

private fun mapOfNotNullValues(vararg entries: Pair<String, String?>): Map<String, String> =
  entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
