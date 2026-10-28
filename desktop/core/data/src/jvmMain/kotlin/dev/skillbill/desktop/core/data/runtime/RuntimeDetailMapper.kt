package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ActivityEvent
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFinding
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentResult
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentVerification
import dev.skillbill.desktop.core.domain.model.GoalQaFinding
import dev.skillbill.desktop.core.domain.model.GoalQaResult
import dev.skillbill.desktop.core.domain.model.Incident
import dev.skillbill.desktop.core.domain.model.IncidentEvidence
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisiteDetail
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.domain.model.IncidentRecovery
import dev.skillbill.desktop.core.domain.model.IncidentRecoveryEligibility
import dev.skillbill.desktop.core.domain.model.IncidentRecoveryPreview
import dev.skillbill.desktop.core.domain.model.IncidentRecoveryUnavailableReason
import dev.skillbill.desktop.core.domain.model.IncidentReport
import dev.skillbill.desktop.core.domain.model.IncidentTelemetry
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.PhaseConclusion
import dev.skillbill.desktop.core.domain.model.PlanRunReport
import dev.skillbill.desktop.core.domain.model.PlannedSubtask
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.ReasonRestoration
import dev.skillbill.desktop.core.domain.model.ReviewFinding
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SubtaskDependency
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.SubtaskValidation
import dev.skillbill.desktop.core.domain.model.ValidationCommand
import dev.skillbill.desktop.core.domain.model.ValidationGateRun
import dev.skillbill.desktop.core.domain.model.ValidationResult
import dev.skillbill.desktop.core.domain.model.WorkDependency
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkPhase
import dev.skillbill.desktop.core.domain.model.WorkQaOutcome
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.domain.model.WorktreeEdit
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.ports.idestatus.model.ExecutionReasonRestoration
import skillbill.ports.idestatus.model.IdeStatusWorkflowQaOutcome
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkIncidentProgress
import skillbill.ports.idestatus.model.WorkIncidentProjection
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryUnavailableReason
import java.nio.file.Path
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState as RuntimeAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification as RuntimeAssessmentVerification
import java.time.Instant as JavaInstant

/**
 * The detail header: the row's fields plus the feature title, the executing agent (the goal's active agent, or the run
 * options' agent for a standalone phase) and, for anything but a standalone phase, the checked-out branch.
 */
internal fun WorkDetailItem.toHeader(options: WorkRunOptions?, branches: CheckedOutBranchSource, sessionId: Long): WorkItem {
  val item = toWorkItem(sessionId)
  val standalone = snapshot.execution?.scope == IdeStatusExecutionScope.STANDALONE_PHASE
  val branch = if (standalone) item.branch else branches.checkedOutBranch(Path.of(repositoryRoot)) ?: item.branch
  return item.copy(
    title = goalManifest?.featureName ?: item.title,
    agentId = goalStatus?.activeAgent ?: options?.agentId?.takeIf { standalone },
    branch = branch,
  )
}

internal fun WorkRunOptions.toDomain() = RunOptions(canCancel, canLaunch, launchLabel, agentId, agentChoices, unavailableReason)

internal fun WorkDetailList.toDomain(): DetailList = when (this) {
  WorkDetailList.SUBTASKS -> DetailList.Subtasks
  WorkDetailList.PHASES -> DetailList.Phases
  WorkDetailList.VALIDATION -> DetailList.Validation
  WorkDetailList.EXECUTIONS -> DetailList.Executions
  WorkDetailList.DEPENDENCIES -> DetailList.Dependencies
}

internal fun DetailList.toRuntime(): WorkDetailList = when (this) {
  DetailList.Subtasks -> WorkDetailList.SUBTASKS
  DetailList.Phases -> WorkDetailList.PHASES
  DetailList.Validation -> WorkDetailList.VALIDATION
  DetailList.Executions -> WorkDetailList.EXECUTIONS
  DetailList.Dependencies -> WorkDetailList.DEPENDENCIES
}

/** Ported from desktop-tray's `WorkDetailItem.toDomain`, without the header fields, which [toHeader] maps. */
internal fun WorkDetailItem.toDetail(observedAt: JavaInstant): Detail {
  val goal = goalStatus
  return Detail(
    featureTitle = goalManifest?.featureName,
    workflowId = snapshot.workflowId,
    invocationId = snapshot.execution?.invocationId,
    activeAgent = goal?.activeAgent,
    executionLiveness = goal?.executionLiveness?.wireValue,
    updatedAt = snapshot.updatedAt.toKotlinInstant(),
    lastAgentActivityLabel = snapshot.lastAgentActivityLabel?.wireValue,
    lastAgentActivityAt = snapshot.lastAgentActivityAt?.toKotlinInstant(),
    currentSubtaskStartedAt = snapshot.currentSubtask?.startedAt?.toKotlinInstant(),
    subtaskActiveDurationMs = snapshot.currentSubtask?.activeDurationMs,
    subtaskActiveDurationAsOf = snapshot.currentSubtask?.activeDurationAsOf?.toKotlinInstant(),
    qaVerdict = snapshot.execution?.qaOutcome?.toDomain(),
    qaOutcome = snapshot.workflowQaOutcome?.toDomain(),
    stepPipeline = stepPipeline,
    goalFinishPipeline = goalFinishPipeline,
    currentPhase = runtimeStatus?.currentPhase ?: goal?.currentStep,
    completeCount = goal?.completeCount ?: runtimeStatus?.completeCount ?: 0,
    pendingCount = goal?.pendingCount ?: runtimeStatus?.pendingCount ?: 0,
    blockedCount = goal?.blockedCount ?: runtimeStatus?.blockedCount ?: 0,
    subtasks = goalManifest?.subtasks.orEmpty().map { subtask ->
      WorkSubtask(
        subtask.id, subtask.name, subtask.status, subtask.commitSha, subtask.blockedReason,
        subtask.dependencies.map { WorkDependency(it.subtaskId, it.optional, it.skipped) },
      )
    },
    phases = runtimeStatus?.phases.orEmpty().map { WorkPhase(it.phaseId, it.status, it.attemptCount, it.finished) },
    dependencies = dependencies.map { entry ->
      SubtaskDependency(entry.subtaskId, WorkDependency(entry.dependency.subtaskId, entry.dependency.optional, entry.dependency.skipped))
    },
    attempts = DetailAttempts(
      goal?.phaseAttemptCounts.orEmpty(), goal?.cumulativeFixIterations.orEmpty(), goal?.reAttemptCauseCounts.orEmpty(),
      goal?.findingsInScope, goal?.supervisorKillCount ?: 0,
    ),
    validation = goal?.completedSubtaskValidation.orEmpty().map { validation ->
      SubtaskValidation(
        validation.subtaskId, validation.validationPassed, validation.integrityProblem,
        validation.evidence?.results.orEmpty().map { ValidationCommand(it.command, it.exitCode) },
        validation.gateExecutionEvidence?.let { evidence ->
          ValidationResult(
            evidence.validationStatus, evidence.checks, evidence.gateRunCount,
            evidence.gateRuns.map { run ->
              ValidationGateRun(
                run.durationMs, run.outcome.wireValue, run.cacheMode.wireValue, run.executedWorkUnits, run.executedChecks,
                run.command, run.exitCode,
              )
            },
          )
        },
      )
    },
    phaseResults = goal?.completedSubtaskPhaseResults.orEmpty().map { result ->
      SubtaskPhaseResults(
        result.subtaskId,
        result.reviewPasses.map { pass ->
          ReviewPass(pass.passNumber, pass.verdict, pass.unresolvedFindingCount, pass.findings.map { ReviewFinding(it.severity, it.label, it.text) })
        },
        result.audit?.let { PhaseConclusion(it.verdict, it.summary, it.detail, it.attemptCount) },
        result.review?.let { PhaseConclusion(it.verdict, it.summary, it.detail, it.attemptCount) },
      )
    },
    goalQa = goal?.goalQaResult?.let { qa ->
      GoalQaResult(
        qa.outcome, qa.reason, qa.qaFixAttempts, qa.recordedAt.toKotlinInstant(),
        qa.findings.map { GoalQaFinding(it.id, it.title, it.evidencePath, it.fixed) }, qa.reportText, qa.failureKind,
      )
    },
    goalAssessment = goal?.goalAssessmentResult?.let { assessment ->
      GoalAssessmentResult(
        assessment.state.toDomain(), assessment.round, assessment.exitReason?.wireValue, assessment.fixRan,
        assessment.findings.map {
          GoalAssessmentFinding(
            it.ref, it.severity.wireValue, it.path, it.line, it.failureScenario, it.verification.toDomain(), it.state.toDomain(),
            it.evidence,
          )
        },
      )
    },
    latestActivity = LatestActivity(
      event = goal?.latestObservabilityEvent?.let {
        ActivityEvent(it.workflowPhase, it.workerRole, it.activitySummary, it.timestamp.toKotlinInstant())
      },
      edit = goal?.latestWorktreeEdit?.let {
        WorktreeEdit(it.recordedAt.toKotlinInstant(), it.pathSample, it.netInsertions, it.netDeletions, it.phaseId)
      },
    ),
    executionHistory = executionHistory.map {
      ExecutionInstance(
        it.invocationId, it.executionId, it.lifecycleState, it.startedAt.toKotlinInstant(), it.updatedAt.toKotlinInstant(),
        it.finishedAt?.toKotlinInstant(), it.result, it.restoration?.toDomain(), it.qaOutcome?.toDomain(), it.qaReportText,
        it.planReport?.toDomain(),
      )
    },
    incident = incident?.toDomain(),
    incidentHistory = incidentHistory.map { it.toDomain() },
    goalSpecBundle = goalSpecBundle?.toDomain(),
    observedAt = observedAt.toKotlinInstant(),
  )
}

private fun ExecutionReasonRestoration.toDomain(): ReasonRestoration = when (this) {
  ExecutionReasonRestoration.EXACT -> ReasonRestoration.Exact
  ExecutionReasonRestoration.RESTORED -> ReasonRestoration.Restored
  ExecutionReasonRestoration.MISSING -> ReasonRestoration.Missing
  ExecutionReasonRestoration.AMBIGUOUS -> ReasonRestoration.Ambiguous
  ExecutionReasonRestoration.BUDGET_EXCEEDED -> ReasonRestoration.BudgetExceeded
  ExecutionReasonRestoration.MISSING_CONTENT -> ReasonRestoration.MissingContent
}

private fun StandaloneQaOutcome.toDomain(): QaVerdict = when (this) {
  StandaloneQaOutcome.ATTEMPTED -> QaVerdict.Attempted
  StandaloneQaOutcome.PASSED -> QaVerdict.Passed
  StandaloneQaOutcome.FINDINGS -> QaVerdict.Findings
  StandaloneQaOutcome.SKIPPED -> QaVerdict.Skipped
  StandaloneQaOutcome.INCOMPLETE -> QaVerdict.Incomplete
}

/** An unparseable `recordedAt` drops the QA outcome for this item rather than failing the detail. */
private fun IdeStatusWorkflowQaOutcome.toDomain(): WorkQaOutcome? =
  runCatching { Instant.parse(recordedAt) }.getOrNull()?.let { WorkQaOutcome(outcome.wireValue, reason, failureKind, it) }

private fun StandalonePlanReport.toDomain(): PlanRunReport =
  PlanRunReport(featureName, parentSpecPath, subtasks.map { PlannedSubtask(it.id, it.name, it.specPath, it.dependsOn) }, bundleFiles)

private fun GoalRunnerAssessmentState.toDomain(): GoalAssessmentState = when (this) {
  GoalRunnerAssessmentState.PENDING -> GoalAssessmentState.Pending
  GoalRunnerAssessmentState.RUNNING -> GoalAssessmentState.Running
  GoalRunnerAssessmentState.COMPLETED -> GoalAssessmentState.Completed
  GoalRunnerAssessmentState.SKIPPED -> GoalAssessmentState.Skipped
}

private fun RuntimeAssessmentVerification.toDomain(): GoalAssessmentVerification = when (this) {
  RuntimeAssessmentVerification.CONFIRMED -> GoalAssessmentVerification.Confirmed
  RuntimeAssessmentVerification.REFUTED -> GoalAssessmentVerification.Refuted
  RuntimeAssessmentVerification.UNVERIFIED -> GoalAssessmentVerification.Unverified
  RuntimeAssessmentVerification.NOT_REQUIRED -> GoalAssessmentVerification.NotRequired
}

private fun RuntimeAssessmentFindingState.toDomain(): GoalAssessmentFindingState = when (this) {
  RuntimeAssessmentFindingState.OPEN -> GoalAssessmentFindingState.Open
  RuntimeAssessmentFindingState.FIXED -> GoalAssessmentFindingState.Fixed
  RuntimeAssessmentFindingState.REFUTED -> GoalAssessmentFindingState.Refuted
}

private fun WorkIncidentProjection.toDomain(): Incident = Incident(
  incidentToken, revision, progress.toDomain(),
  summary, cause, certainty.wireValue, responsibleCategory.wireValue, nextAction, noWorkaroundKnown,
  prerequisites.map { IncidentPrerequisiteDetail(it.kind, it.instruction) },
  evidence.map { IncidentEvidence(it.kind.wireValue, it.referenceId, it.summary, it.trusted) },
  degradations.map { it.wireValue }.toSet(),
  IncidentRecovery(
    when (val value = recovery.eligibility) {
      is BlockIncidentRecoveryEligibilityResult.Available -> IncidentRecoveryEligibility.Available(value.preview.toDomain())
      is BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites -> IncidentRecoveryEligibility.NeedsPrerequisites(
        value.instructions.map { IncidentPrerequisiteDetail(it.kind.wireValue, it.instruction) },
      )
      is BlockIncidentRecoveryEligibilityResult.Unavailable ->
        IncidentRecoveryEligibility.Unavailable(value.reason.toDomain())
      null -> null
    },
    recovery.previewLabel, recovery.effects, recovery.targetWorkflowId, recovery.targetInvocationId, recovery.latestAttemptId,
    recovery.latestAttemptStatus?.wireValue, recovery.failure?.wireValue,
  ),
  IncidentReport(report.status.wireValue, report.failureReason?.wireValue, report.issueUrl),
  IncidentTelemetry(
    telemetry.availability,
    listOf(
      "install_id" to telemetry.installId,
      "incident_token" to telemetry.incidentToken,
      "workflow_id" to telemetry.workflowId,
      "invocation_id" to telemetry.invocationId,
    ).mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
  ),
  observedAt.toKotlinInstant(),
  settledAt?.toKotlinInstant(),
)

private fun BlockIncidentRecoveryPreview.toDomain() =
  IncidentRecoveryPreview(action, label, effects, targetWorkflowId, targetInvocationId, phaseId, incidentToken, incidentRevision)

private fun WorkIncidentProgress.toDomain(): IncidentProgress = when (this) {
  WorkIncidentProgress.INVESTIGATION_PENDING -> IncidentProgress.InvestigationPending
  WorkIncidentProgress.INVESTIGATING -> IncidentProgress.Investigating
  WorkIncidentProgress.DIAGNOSIS_COMPLETE -> IncidentProgress.DiagnosisComplete
  WorkIncidentProgress.DIAGNOSIS_PARTIAL -> IncidentProgress.DiagnosisPartial
  WorkIncidentProgress.DIAGNOSIS_FAILED -> IncidentProgress.DiagnosisFailed
  WorkIncidentProgress.NEEDS_PREREQUISITES -> IncidentProgress.NeedsPrerequisites
  WorkIncidentProgress.RECOVERY_AVAILABLE -> IncidentProgress.RecoveryAvailable
  WorkIncidentProgress.RECOVERING -> IncidentProgress.Recovering
  WorkIncidentProgress.RECOVERY_FAILED -> IncidentProgress.RecoveryFailed
  WorkIncidentProgress.RESUMED -> IncidentProgress.Resumed
}

private fun BlockIncidentRecoveryUnavailableReason.toDomain(): IncidentRecoveryUnavailableReason = when (this) {
  BlockIncidentRecoveryUnavailableReason.NO_SUPPORTED_ACTION -> IncidentRecoveryUnavailableReason.NoSupportedAction
  BlockIncidentRecoveryUnavailableReason.INVESTIGATION_NOT_SETTLED -> IncidentRecoveryUnavailableReason.InvestigationNotSettled
  BlockIncidentRecoveryUnavailableReason.NOT_BLOCKED -> IncidentRecoveryUnavailableReason.NotBlocked
  BlockIncidentRecoveryUnavailableReason.GENERATION_CHANGED -> IncidentRecoveryUnavailableReason.GenerationChanged
  BlockIncidentRecoveryUnavailableReason.ATTEMPT_LIMIT_REACHED -> IncidentRecoveryUnavailableReason.AttemptLimitReached
  BlockIncidentRecoveryUnavailableReason.OPERATOR_DECISION -> IncidentRecoveryUnavailableReason.OperatorDecision
  BlockIncidentRecoveryUnavailableReason.QUALITY_GATE -> IncidentRecoveryUnavailableReason.QualityGate
}
