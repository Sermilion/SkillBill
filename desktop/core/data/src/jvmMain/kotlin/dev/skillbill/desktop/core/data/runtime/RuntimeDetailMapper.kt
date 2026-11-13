package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ActivityEvent
import dev.skillbill.desktop.core.domain.model.AuditAttempt
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.ValidationBranchRun
import dev.skillbill.desktop.core.domain.model.ValidationCheck
import dev.skillbill.desktop.core.domain.model.ValidationCheckAttempt
import dev.skillbill.desktop.core.domain.model.ValidationCheckResult
import dev.skillbill.desktop.core.domain.model.ValidationOutcome
import dev.skillbill.desktop.core.domain.model.PlanAgentActivity
import dev.skillbill.desktop.core.domain.model.PlanOutputLine
import dev.skillbill.desktop.core.domain.model.PlanWave
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFinding
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentResult
import dev.skillbill.desktop.core.domain.model.GoalAssessmentState
import dev.skillbill.desktop.core.domain.model.GoalAssessmentVerification
import dev.skillbill.desktop.core.domain.model.GoalQaPreview
import dev.skillbill.desktop.core.domain.model.GoalQaFinding
import dev.skillbill.desktop.core.domain.model.GoalQaResult
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.QaRunAssertion
import dev.skillbill.desktop.core.domain.model.QaRunCoverage
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunFinding
import dev.skillbill.desktop.core.domain.model.QaRunRetainedResource
import dev.skillbill.desktop.core.domain.model.QaRunTemporaryEdit
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
import dev.skillbill.desktop.core.domain.model.QaChecklist
import dev.skillbill.desktop.core.domain.model.QaChecklistCase
import dev.skillbill.desktop.core.domain.model.QaChecklistFinding
import dev.skillbill.desktop.core.domain.model.QaChecklistStep
import dev.skillbill.desktop.core.domain.model.QaChecklistTarget
import dev.skillbill.desktop.core.domain.model.QaStepState
import dev.skillbill.desktop.core.domain.model.QaTargetState
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.ReasonRestoration
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.ReviewReport
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.domain.model.RunningReviewLane
import dev.skillbill.desktop.core.domain.model.RunningReviewPass
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.SubtaskDependency
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.SubtaskExecution
import dev.skillbill.desktop.core.domain.model.SubtaskValidation
import dev.skillbill.desktop.core.domain.model.StandalonePlanProgress as DesktopStandalonePlanProgress
import dev.skillbill.desktop.core.domain.model.StandalonePlanStage as DesktopStandalonePlanStage
import dev.skillbill.desktop.core.domain.model.StandalonePlanUnit as DesktopStandalonePlanUnit
import dev.skillbill.desktop.core.domain.model.ValidationCommand
import dev.skillbill.desktop.core.domain.model.ValidationGateRun
import dev.skillbill.desktop.core.domain.model.ValidationResult
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.PhaseCardEdit
import dev.skillbill.desktop.core.domain.model.PhaseCardOutput
import dev.skillbill.desktop.core.domain.model.PhaseCardHistoryReceipt
import dev.skillbill.desktop.core.domain.model.CiCycle
import dev.skillbill.desktop.core.domain.model.CiCheck
import dev.skillbill.desktop.core.domain.model.WorkDependency
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import skillbill.ports.idestatus.model.WorkChangeBaseline
import dev.skillbill.desktop.core.domain.model.WorkPhase
import dev.skillbill.desktop.core.domain.model.WorkQaOutcome
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.domain.model.WorktreeEdit
import skillbill.engine.work.control.model.WorkRunOptions
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState as RuntimeAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification as RuntimeAssessmentVerification
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.goalrunner.model.GoalRunnerQaResult
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import skillbill.goalrunner.model.GoalRunnerCiCheck
import skillbill.goalrunner.model.GoalRunnerCiCycle
import skillbill.goalrunner.model.GoalRunnerHistoryReceipt
import skillbill.goalrunner.model.GoalRunnerPhaseCardEdit
import skillbill.goalrunner.model.GoalRunnerPhaseCardOutput
import skillbill.goalrunner.model.GoalRunnerPhaseCard
import skillbill.goalrunner.model.GoalRunnerPhaseCardState
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.ports.idestatus.model.ExecutionReasonRestoration
import skillbill.ports.idestatus.model.IdeStatusWorkflowQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaChecklist
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandalonePlanProgress as RuntimeStandalonePlanProgress
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaStepStatus
import skillbill.ports.idestatus.model.StandaloneQaTargetState
import skillbill.ports.idestatus.model.StandaloneReviewReport
import skillbill.ports.idestatus.model.LiveChecksAvailability
import skillbill.ports.idestatus.model.ValidationCheckOutcome
import skillbill.ports.idestatus.model.StandaloneValidationOutcome
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkExecutionInstance
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.ports.idestatus.model.WorkDetailList
import skillbill.ports.idestatus.model.WorkIncidentProgress
import skillbill.ports.idestatus.model.WorkIncidentProjection
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.review.model.ReviewFindingStatus as RuntimeReviewFindingStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryUnavailableReason
import java.nio.file.Path
import skillbill.workflow.taskruntime.model.qa.QaAssertionOutcome as RuntimeQaAssertionOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunDetails as RuntimeQaRunDetails
import java.time.Instant as JavaInstant
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * The detail header: the row's fields plus, for a goal, the manifest's feature title, the executing agent (the goal's active agent, or the run
 * options' agent for a standalone phase) and, for anything but a standalone phase, the checked-out branch.
 */
private const val GOAL_STATUS_READ_SOURCE = "goal_status"

internal fun WorkDetailItem.toHeader(options: WorkRunOptions?, branches: CheckedOutBranchSource, sessionId: Long): WorkItem {
  val item = toWorkItem(sessionId)
  val goalWorktree = item.goalWorktree
  val standalone = snapshot.execution?.scope == IdeStatusExecutionScope.STANDALONE_PHASE
  val branch =
    when (goalWorktree?.state) {
      GoalWorktreeState.Active, GoalWorktreeState.Retained ->
        branches.checkedOutBranch(Path.of(goalWorktree.path)) ?: item.branch
      GoalWorktreeState.Removed ->
        (this.changeBaseline as? WorkChangeBaseline.CommittedBranchRange)?.headBranch ?: item.branch
      null -> if (standalone) item.branch else branches.checkedOutBranch(Path.of(repositoryRoot)) ?: item.branch
    }
  val planFeature =
    selectedExecution?.takeIf { it.invocationId == snapshot.execution?.invocationId }?.planReport?.featureName
      ?: executionHistory.firstOrNull { it.invocationId == snapshot.execution?.invocationId }?.planReport?.featureName
  return item.copy(
    title = goalManifest?.featureName?.takeIf { item.kind == WorkKind.Goal && it.isNotBlank() }?.let { displayTitle(it, item.issueKey) }
      ?: planFeature?.takeIf { standalone && it.isNotBlank() }?.let { displayTitle(it, item.issueKey) } ?: item.title,
    agentId = goalStatus?.activeAgent ?: options?.agentId?.takeIf { standalone },
    branch = branch,
    launchWarning = goalStatus?.launchWarning,
  )
}

internal fun WorkRunOptions.toDomain() = RunOptions(canCancel, canLaunch, launchLabel, agentId, agentChoices, unavailableReason, goalStarted, canFinalize)

internal fun WorkDetailList.toDomain(): DetailList = when (this) {
  WorkDetailList.SUBTASKS -> DetailList.Subtasks
  WorkDetailList.PHASES -> DetailList.Phases
  WorkDetailList.VALIDATION -> DetailList.Validation
  WorkDetailList.EXECUTIONS -> DetailList.Executions
  WorkDetailList.PLAN_OUTPUT -> DetailList.PlanOutput
  WorkDetailList.DEPENDENCIES -> DetailList.Dependencies
}

internal fun DetailList.toRuntime(): WorkDetailList = when (this) {
  DetailList.Subtasks -> WorkDetailList.SUBTASKS
  DetailList.Phases -> WorkDetailList.PHASES
  DetailList.Validation -> WorkDetailList.VALIDATION
  DetailList.Executions -> WorkDetailList.EXECUTIONS
  DetailList.PlanOutput -> WorkDetailList.PLAN_OUTPUT
  DetailList.Dependencies -> WorkDetailList.DEPENDENCIES
}

/** Maps a work detail item, without the header fields, which [toHeader] maps. */
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
    currentActivity = snapshot.currentActivity,
    currentSubtaskStartedAt = snapshot.currentSubtask?.startedAt?.toKotlinInstant(),
    subtaskActiveDurationMs = snapshot.currentSubtask?.activeDurationMs,
    subtaskActiveDurationAsOf = snapshot.currentSubtask?.activeDurationAsOf?.toKotlinInstant(),
    qaVerdict = snapshot.execution?.qaOutcome?.toDomain(),
    qaOutcome = snapshot.workflowQaOutcome?.toDomain(),
    stepPipeline = stepPipeline,
    goalFinishPipeline = goalFinishPipeline,
    currentPhase = runtimeStatus?.currentPhase ?: goal?.currentStep ?: snapshot.currentStep.id,
    completeCount = goal?.completeCount ?: runtimeStatus?.completeCount ?: 0,
    pendingCount = goal?.pendingCount ?: runtimeStatus?.pendingCount ?: 0,
    blockedCount = goal?.blockedCount ?: runtimeStatus?.blockedCount ?: 0,
    subtasks = goalManifest?.subtasks.orEmpty().map { subtask ->
      WorkSubtask(
        subtask.id, subtask.name, subtask.status, subtask.commitSha, subtask.blockedReason,
        subtask.dependencies.map { WorkDependency(it.subtaskId, it.optional, it.skipped) },
      )
    },
    phases = runtimeStatus?.phases.orEmpty().map { WorkPhase(it.phaseId, it.status, it.attemptCount, it.finished, it.briefingText) },
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
        validation.gateExecutionEvidence?.toDomain(),
      )
    },
    phaseResults = goal?.completedSubtaskPhaseResults.orEmpty().map { it.toDomain() },
    goalFinishCards = goal?.goalFinishCards.orEmpty().map { it.toDomain() },
    goalQa = goal?.goalQaResult?.toDomain(),
    goalQaPreview = qaPreview?.let { preview ->
      GoalQaPreview(preview.invocationId, preview.invocationDirectory, preview.targets, preview.active, qaPreviewSubtaskId)
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
        assessment.fixCommitSha,
        assessment.unavailableReason,
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
    executionHistory = executionHistory.map { it.toDomain() },
    selectedExecution = selectedExecution?.toDomain(),
    validationBranchRuns = validationBranchRuns.map {
      ValidationBranchRun(
        invocationId = it.invocationId,
        runSequence = it.runSequence,
        lifecycle = IdeStatusPolicy.standaloneLifecycle(it.lifecycleState).toDomain(),
        startedAt = it.startedAt.toKotlinInstant(),
        finishedAt = it.finishedAt?.toKotlinInstant(),
        activeDurationMs = it.activeDurationMs,
        agent = it.launchAgentId,
        currentActivity = it.currentActivity,
        result = it.result,
      )
    },
    selectedValidationResult = selectedValidationResult,
    incident = incident?.toDomain(),
    incidentHistory = incidentHistory.map { it.toDomain() },
    goalSpecBundle = goalSpecBundle?.toDomain(),
    observedAt = observedAt.toKotlinInstant(),
    changeBaseline = changeBaseline?.toDomain(),
    degradedGoalStatusSource = GOAL_STATUS_READ_SOURCE.takeIf { goal == null && !goalManifest?.subtasks.isNullOrEmpty() },
    currentSubtaskId = goal?.currentSubtaskId,
    goalWorktree = goalWorktree?.toDomainWorktree(),
    launchWarning = goal?.launchWarning,
    planActivity = planActivity.map { record ->
      val event = record.event
      PlanAgentActivity(event.id, record.phaseId, event.kind.wireValue, event.text, record.at.toKotlinInstant(), record.updatedAt.toKotlinInstant(), event.agentId, event.path, event.state.wireValue, event.durationMs, event.exitCode, event.subtaskId)
    },
    planOutputLines = planOutputLines.map { PlanOutputLine(it.id, it.text, it.at.toKotlinInstant(), it.subtaskId, it.sequence, it.isRaw) },
    planOutputTail = planOutputTail,
    planOutputUpdatedAt = planOutputUpdatedAt?.toKotlinInstant(),
    planSubtaskLatestLines = planSubtaskLatestLines,
    standalonePlan = standalonePlanProgress?.toDomain(),
    verifyRun = verifyRun?.toDomain(),
  )
}

private fun StandaloneValidationOutcome.toDomain(): ValidationOutcome = when (this) {
  StandaloneValidationOutcome.PASSED -> ValidationOutcome.Passed
  StandaloneValidationOutcome.FAILED -> ValidationOutcome.Failed
  StandaloneValidationOutcome.BLOCKED -> ValidationOutcome.Blocked
}

private fun WorkExecutionInstance.toDomain() = ExecutionInstance(
  invocationId, executionId, lifecycleState, startedAt.toKotlinInstant(), updatedAt.toKotlinInstant(),
  finishedAt?.toKotlinInstant(), result, restoration?.toDomain(), qaOutcome?.toDomain(), qaReportText,
  planReport?.toDomain(), reviewReport?.toDomain(),
  qaChecklist?.toDomain(), qaFindings.map { finding -> QaChecklistFinding(finding.id, finding.title, null) },
  qaDetails?.toDomain(),
  liveChecks = liveChecks == LiveChecksAvailability.AVAILABLE,
  validationOutcome = validationOutcome?.toDomain(),
  validationChecks = validationChecks.map { check ->
    ValidationCheck(check.checkId, check.name, check.command, check.workingDir, check.attempts.map { attempt ->
      ValidationCheckAttempt(
        attempt.number, attempt.startedAt?.toKotlinInstant(), attempt.finishedAt?.toKotlinInstant(),
        attempt.outcome?.toDomain(), attempt.exitCode, attempt.note,
      )
    })
  },
)

private fun ValidationCheckOutcome.toDomain(): ValidationCheckResult = when (this) {
  ValidationCheckOutcome.PASSED -> ValidationCheckResult.Passed
  ValidationCheckOutcome.FAILED -> ValidationCheckResult.Failed
  ValidationCheckOutcome.BLOCKED -> ValidationCheckResult.Blocked
  ValidationCheckOutcome.SKIPPED -> ValidationCheckResult.Skipped
}

private fun StandaloneQaChecklist.toDomain(): QaChecklist = QaChecklist(
  invocationDirectory, reportPath, testedCommit, criteriaSource,
  targets.map { target -> QaChecklistTarget(target.name, target.state.toDomain(), target.detail, target.updatedAt?.toKotlinInstant()) },
  planRegisteredAt?.toKotlinInstant(),
  cases.map { case -> QaChecklistCase(case.id, case.title, case.steps.map { step ->
    QaChecklistStep(step.text, step.status.toDomain(), step.note, step.findingId, step.evidence, step.updatedAt?.toKotlinInstant())
  }) },
  findings.map { finding -> QaChecklistFinding(finding.id, finding.title, finding.recordedAt.toKotlinInstant()) },
  lastStepUpdateAt?.toKotlinInstant(),
)

private fun StandaloneQaStepStatus.toDomain(): QaStepState = when (this) {
  StandaloneQaStepStatus.PENDING -> QaStepState.Pending
  StandaloneQaStepStatus.RUNNING -> QaStepState.Running
  StandaloneQaStepStatus.PASSED -> QaStepState.Passed
  StandaloneQaStepStatus.FAILED -> QaStepState.Failed
  StandaloneQaStepStatus.SKIPPED -> QaStepState.Skipped
}

private fun StandaloneQaTargetState.toDomain(): QaTargetState = when (this) {
  StandaloneQaTargetState.PENDING -> QaTargetState.Pending
  StandaloneQaTargetState.RUNNING -> QaTargetState.Running
  StandaloneQaTargetState.READY -> QaTargetState.Ready
  StandaloneQaTargetState.SKIPPED -> QaTargetState.Skipped
}

internal fun GoalRunnerSubtaskPhaseResults.toDomain(): SubtaskPhaseResults = SubtaskPhaseResults(
  subtaskId,
  reviewPasses.map { pass ->
    ReviewPass(
      pass.passNumber, pass.verdict, pass.unresolvedFindingCount,
      pass.findings.map { ReviewReportFinding(it.ref, it.severity, it.confidence, it.specialist, it.location, it.description, it.status.toDomain()) },
      pass.lanes,
    )
  },
  audit?.let { PhaseConclusion(it.verdict, it.summary, it.detail, it.attemptCount) },
  review?.let { PhaseConclusion(it.verdict, it.summary, it.detail, it.attemptCount) },
  degradedReadSource,
  runningReviewPass?.let { pass ->
    RunningReviewPass(pass.passNumber, pass.lanes.map { lane -> RunningReviewLane(lane.name, lane.finished) })
  },
  auditResult?.let { audit ->
    SubtaskAuditResult(
      outcome = when (audit.outcome) {
        skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome.PASSED -> AuditOutcome.Passed
        skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome.FAILED -> AuditOutcome.Failed
        skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome.RUNNING -> AuditOutcome.Running
        skillbill.goalrunner.model.GoalRunnerSubtaskAuditOutcome.UNKNOWN -> AuditOutcome.Unknown
      },
      metCount = audit.metCount,
      totalCount = audit.totalCount,
      criteria = audit.criteria.map { AuditCriterion(it.ref, it.text, it.met, it.fixedInAttempt) },
      attempts = audit.attempts.map {
        AuditAttempt(it.number, it.passed, it.uncoveredCount, it.nextPhaseId, it.unavailableReason)
      },
      attemptTotal = audit.attemptTotal,
    )
  },
  execution = execution?.let {
    SubtaskExecution(
      workflowId = it.workflowId,
      agentId = it.agentId,
      model = it.model,
      effort = it.effort,
      startedAt = it.startedAt.toKotlinInstant(),
      updatedAt = it.updatedAt.toKotlinInstant(),
      phaseAttemptCounts = it.phaseAttemptCounts,
    )
  },
  phaseCards = phaseCards.map { it.toDomain() },
)

private fun GoalRunnerPhaseCard.toDomain(): PhaseCard = PhaseCard(
  phaseId = phaseId,
  state = when (state) {
    GoalRunnerPhaseCardState.RUNNING -> PhaseCardState.Running
    GoalRunnerPhaseCardState.COMPLETED -> PhaseCardState.Done
    GoalRunnerPhaseCardState.FAILED -> PhaseCardState.Failed
    GoalRunnerPhaseCardState.BLOCKED -> PhaseCardState.Blocked
    GoalRunnerPhaseCardState.SKIPPED -> PhaseCardState.Skipped
    GoalRunnerPhaseCardState.PAUSED -> PhaseCardState.Paused
  },
  round = round,
  startedAt = startedAt?.toKotlinInstant(),
  finishedAt = finishedAt?.toKotlinInstant(),
  durationMillis = durationMillis,
  agentId = agentId,
  model = model,
  effort = effort,
  blockedReason = blockedReason,
  files = files,
  edit = edit?.toDomain(),
  activityLabel = activityLabel?.wireValue,
  activityAt = activityAt?.toKotlinInstant(),
  output = output?.toDomain(),
  pullRequestUrl = pullRequestUrl,
  pullRequestNumber = pullRequestNumber,
  ciCycles = ciCycles.map { it.toDomain() },
  repairedChecks = repairedChecks.map { it.toDomain() },
  conflictBaseBranch = conflictBaseBranch,
  fixCommitSha = fixCommitSha,
)

private fun GoalRunnerPhaseCardEdit.toDomain(): PhaseCardEdit = PhaseCardEdit(
  recordedAt.toKotlinInstant(), fileCount, insertions, deletions, samplePaths,
)

private fun GoalRunnerPhaseCardOutput.toDomain(): PhaseCardOutput = PhaseCardOutput(
  summary = summary,
  text = text,
  planRecord = planRecord?.let {
    dev.skillbill.desktop.core.domain.model.PhaseCardPlanRecord(it.manifestOrder, it.specPath, it.specHash, it.payloadHash, it.repair)
  },
  verdict = verdict,
  buildReceipt = buildReceipt?.toDomain(),
  validationReceipt = validationReceipt?.toDomain(),
  historyReceipt = historyReceipt?.toDomain(),
  commitSha = commitSha,
  pushedBranch = pushedBranch,
  pushed = pushed,
  pullRequestUrl = pullRequestUrl,
  pullRequestNumber = pullRequestNumber,
)

private fun GoalRunnerHistoryReceipt.toDomain(): PhaseCardHistoryReceipt = PhaseCardHistoryReceipt(
  changedPaths, historyWritten, decisionsRecorded,
)

private fun GoalRunnerCiCycle.toDomain(): CiCycle = CiCycle(
  cycle, startedAt.toKotlinInstant(), observedAt.toKotlinInstant(), finishedAt?.toKotlinInstant(), outcome, headSha,
  checks.map { it.toDomain() },
)

private fun GoalRunnerCiCheck.toDomain(): CiCheck = CiCheck(
  name, bucket, link, startedAt?.toKotlinInstant(), completedAt?.toKotlinInstant(), durationMillis,
)

private fun FeatureTaskRuntimeValidationGateExecutionEvidence.toDomain(): ValidationResult = ValidationResult(
  validationStatus, checks, gateRunCount,
  gateRuns.map { run ->
    ValidationGateRun(
      run.durationMs, run.outcome.wireValue, run.cacheMode.wireValue, run.executedWorkUnits, run.executedChecks,
      run.command, run.exitCode,
    )
  },
)

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
  runCatching { Instant.parse(recordedAt) }.getOrNull()?.let { WorkQaOutcome(outcome.toDomain(), reason, failureKind, it) }

private fun StandaloneReviewReport.toDomain(): ReviewReport =
  ReviewReport(
    verdict, summary,
    findings.map { ReviewReportFinding(it.ref, it.severity, it.confidence, it.specialist, it.location, it.description, it.status.toDomain()) },
    lanes,
    findingActions.flatMap { it.toActivityFacts() },
  )

private fun StandalonePlanReport.toDomain(): PlanRunReport =
  PlanRunReport(featureName, parentSpecPath, subtasks.map { PlannedSubtask(it.id, it.name, it.specPath, it.dependsOn) }, bundleFiles)

private fun RuntimeStandalonePlanProgress.toDomain(): DesktopStandalonePlanProgress = DesktopStandalonePlanProgress(
  report?.toDomain(),
  stages.map {
    DesktopStandalonePlanStage(
      it.phaseId,
      it.firstStartedAt?.toKotlinInstant(),
      it.finishedAt?.toKotlinInstant(),
      it.briefingText,
      it.status,
    )
  },
  plannedSubtaskCount,
  totalSubtaskCount,
  currentPlanningSubtaskId,
  planningWaveSubtaskIds,
  planningWaveCapacity,
  blockedSubtaskId,
  blockedReason,
  units.map { unit ->
    DesktopStandalonePlanUnit(
      unit.subtaskId, unit.name, unit.specPath, unit.dependsOn, unit.state.wireValue,
      unit.startedAt?.toKotlinInstant(), unit.finishedAt?.toKotlinInstant(), unit.blockedReason, unit.latestOutput,
      unit.latestOutputAt?.toKotlinInstant(),
    )
  },
  stalledSince?.toKotlinInstant(),
  planningWaveNumber,
  planningWaveHistoryComplete,
  blockedSubtaskIds,
  waves.map { PlanWave(it.number, it.subtaskIds, it.startedAt?.toKotlinInstant()) },
)

private fun GoalRunnerAssessmentState.toDomain(): GoalAssessmentState = when (this) {
  GoalRunnerAssessmentState.BLOCKED -> GoalAssessmentState.Blocked
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

internal fun RuntimeReviewFindingStatus.toDomain(): ReviewFindingStatus = when (this) {
  RuntimeReviewFindingStatus.OPEN -> ReviewFindingStatus.Open
  RuntimeReviewFindingStatus.FIXED -> ReviewFindingStatus.Fixed
  RuntimeReviewFindingStatus.ACCEPTED -> ReviewFindingStatus.Accepted
  RuntimeReviewFindingStatus.DECLINED -> ReviewFindingStatus.Declined
  RuntimeReviewFindingStatus.FALSE_POSITIVE -> ReviewFindingStatus.FalsePositive
  RuntimeReviewFindingStatus.REJECTED -> ReviewFindingStatus.Rejected
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

internal fun GoalRunnerQaResult.toDomain() = GoalQaResult(
  outcome, reason, qaFixAttempts, recordedAt.toKotlinInstant(),
  findings.map { GoalQaFinding(it.id, it.title, it.fixed, it.evidencePaths, it.reportPath, it.observed, it.expected, it.steps) },
  reportText,
  failureKind,
  details?.toDomain(),
)

internal fun RuntimeQaRunDetails.toDomain() = QaRunDetails(
  reportPath, invocationDirectory, skipReason,
  assertions.map { assertion ->
    val outcome = when (assertion.outcome) {
      RuntimeQaAssertionOutcome.PASSED -> QaAssertionOutcome.Passed
      RuntimeQaAssertionOutcome.FAILED -> QaAssertionOutcome.Failed
      RuntimeQaAssertionOutcome.NOT_EXERCISED -> QaAssertionOutcome.NotExercised
    }
    QaRunAssertion(outcome, assertion.title, assertion.detail, assertion.evidencePaths)
  },
  coverage.map { QaRunCoverage(it.kind, it.verdict, it.reason, it.backend) },
  retainedResources.map { QaRunRetainedResource(it.kind, it.identity) },
  temporaryEdits.map { QaRunTemporaryEdit(it.path, it.detail, it.notRestored) },
  findings.map { QaRunFinding(it.id, it.title, it.observed, it.expected, it.steps, it.evidencePaths) },
  historyAvailability,
  occurrences.map { occurrence ->
    dev.skillbill.desktop.core.domain.model.QaRunOccurrence(
      occurrence.occurrenceId,
      occurrence.kind,
      occurrence.phaseAttempt,
      occurrence.workspaceInvocationId,
      occurrence.startedAt,
      occurrence.finishedAt,
      occurrence.disposition,
      occurrence.testedTreeIdentity,
      occurrence.resultReference,
      occurrence.frameReference,
      occurrence.frameAvailability,
      occurrence.screenshots.map { image ->
        dev.skillbill.desktop.core.domain.model.QaRunScreenshot(image.label, image.path, image.recordedAt, image.format)
      },
      occurrence.stages.map { stage ->
        dev.skillbill.desktop.core.domain.model.QaRunStage(stage.name, stage.state, stage.startedAt, stage.finishedAt, stage.detail)
      },
    )
  },
)
