package skillbill.engine.goalrunner.status

import skillbill.engine.goalrunner.model.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityExecutionRead
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAssessmentFinding
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.goalrunner.model.GoalRunnerCiCheck
import skillbill.goalrunner.model.GoalRunnerCiCycle
import skillbill.goalrunner.model.GoalRunnerPhaseCard
import skillbill.goalrunner.model.GoalRunnerPhaseCardState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.MonitorCiCheckBucket
import skillbill.ports.idestatus.model.MonitorCiCycleCheck
import skillbill.ports.idestatus.model.MonitorCiCycleOutcome
import skillbill.ports.idestatus.model.MonitorCiCycleRecord
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Duration

private const val SEAM = "goal-status.goal_assessment_result"
private const val EXPECTED = "goal_assessment_record"

internal fun GoalRunnerWorkflowOutcomeStore.goalAssessmentResult(
  parentWorkflowId: String,
  durableRead: GoalRunnerStatusDurableReadTracker,
): GoalRunnerAssessmentResult? =
  catchingNonCancellation { readGoalAssessmentRecord(parentWorkflowId) }.fold(
    onSuccess = { read ->
      when (read) {
        GoalAssessmentRecordRead.Absent -> null
        is GoalAssessmentRecordRead.Recorded -> read.record.toStatusResult()
        is GoalAssessmentRecordRead.Corrupt -> {
          durableRead.recordDegradedRead(seam = SEAM, expected = EXPECTED, used = "omitted")
          null
        }
      }
    },
    onFailure = { error ->
      durableRead.recordDegradedRead(seam = SEAM, expected = EXPECTED, used = "omitted", error = error)
      null
    },
  )

internal fun GoalRunnerAssessmentResult.reconciledWith(liveness: ExecutionLiveness): GoalRunnerAssessmentResult =
  if (state == GoalRunnerAssessmentState.RUNNING && liveness in DEAD_EXECUTOR_LIVENESS) {
    copy(state = GoalRunnerAssessmentState.PENDING)
  } else {
    this
  }

internal fun GoalRunnerAssessmentResult?.stageStep(step: String?): String? =
  when {
    this == null || exitReason != null || step.isNullOrBlank() -> step
    step in PhaseSlot.GOAL_ASSESSMENT.steps -> step
    else -> FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX
  }

private val DEAD_EXECUTOR_LIVENESS =
  setOf(ExecutionLiveness.IDLE, ExecutionLiveness.LIVE_EXPIRED, ExecutionLiveness.PID_REUSED)

private fun GoalAssessmentRecord.toStatusResult(): GoalRunnerAssessmentResult {
  val findings = rounds.flatMap { it.findings }
  return GoalRunnerAssessmentResult(
    state = statusState(),
    round = statusRound(),
    exitReason = exitReason,
    unavailableReason = unavailableReason,
    fixCommitSha = rounds.lastOrNull { it.fix != null }?.fix?.commitSha,
    fixRan = rounds.mapNotNull { it.fix }.any { it.status != GoalAssessmentFixStatus.NOT_RUN },
    openFindingRefs = findings.filter { it.state == GoalAssessmentFindingState.OPEN }.map { it.findingRef },
    findings =
      findings.map {
        GoalRunnerAssessmentFinding(
          ref = it.findingRef,
          severity = it.severity,
          path = it.path,
          line = it.line,
          failureScenario = it.failureScenario,
          verification = it.verification,
          state = it.state,
          evidence = it.verificationEvidence,
        )
      },
  )
}

private fun GoalAssessmentRecord.statusState(): GoalRunnerAssessmentState =
  when {
    exitReason == GoalAssessmentExitReason.SKIPPED_NO_CHANGES -> GoalRunnerAssessmentState.SKIPPED
    exitReason == GoalAssessmentExitReason.CONVERGED -> GoalRunnerAssessmentState.COMPLETED
    exitReason != null -> GoalRunnerAssessmentState.BLOCKED
    rounds.isEmpty() -> GoalRunnerAssessmentState.PENDING
    else -> GoalRunnerAssessmentState.RUNNING
  }

private fun GoalAssessmentRecord.statusRound(): Int =
  when {
    exitReason != null || rounds.isEmpty() -> rounds.size
    rounds.last().fix != null -> rounds.size + 1
    else -> rounds.size
  }

private const val CYCLE_LIMIT = 10
private val QUALITY_PHASE_IDS =
  listOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
  )

internal data class GoalFinishCardSources(
  val assessment: GoalRunnerAssessmentResult?,
  val quality: GoalQualityExecutionRead?,
  val cycles: List<MonitorCiCycleRecord>?,
  val goalPullRequestUrl: String?,
  val goalCompleted: Boolean = false,
)

internal data class GoalFinishCardReadRequest(
  val parentWorkflowId: String,
  val assessment: GoalRunnerAssessmentResult?,
  val goalPullRequestUrl: String?,
  val currentStep: String?,
  val workflowStatus: WorkflowStatus?,
  val childWorkflowId: String?,
  val goalCompleted: Boolean = false,
)

internal fun readGoalFinishCards(
  request: GoalFinishCardReadRequest,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  database: DatabaseSessionFactory,
  durableRead: GoalRunnerStatusDurableReadTracker,
): List<GoalRunnerPhaseCard> {
  val quality =
    catchingNonCancellation { outcomeStore.retainedGoalQualityExecution(request.parentWorkflowId) }.getOrElse { error ->
      durableRead.recordDegradedRead("goal-status.goal_finish_quality", "goal_quality_execution", "omitted", error)
      null
    }
  val cycles =
    catchingNonCancellation {
      database.read { it.monitorCiCycles.cycles(request.parentWorkflowId, CYCLE_LIMIT) }
    }.getOrElse { error ->
      durableRead.recordDegradedRead("goal-status.monitor_ci_cycles", "monitor_ci_cycles", "omitted", error)
      null
    }
  return goalFinishCards(
    GoalFinishCardSources(request.assessment, quality, cycles, request.goalPullRequestUrl, request.goalCompleted),
    request.currentStep,
    request.workflowStatus,
    request.childWorkflowId,
    durableRead,
  )
}

internal fun goalFinishCards(
  sources: GoalFinishCardSources,
  currentStep: String?,
  workflowStatus: WorkflowStatus?,
  childWorkflowId: String?,
  durableRead: GoalRunnerStatusDurableReadTracker,
): List<GoalRunnerPhaseCard> {
  val cards = mutableListOf<GoalRunnerPhaseCard>()
  sources.assessment?.toAssessmentCard()?.let(cards::add)
  cards += qualityCards(sources.quality, durableRead)
  val cycles = sources.cycles.orEmpty()
  val latestCycleUrl = cycles.lastOrNull { it.pullRequestUrl != null }?.pullRequestUrl
  val currentPrState = currentPullRequestState(currentStep, workflowStatus, childWorkflowId, sources.goalCompleted)
  if (currentPrState != null || sources.goalPullRequestUrl != null || latestCycleUrl != null) {
    cards +=
      GoalRunnerPhaseCard(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        currentPrState ?: GoalRunnerPhaseCardState.COMPLETED,
        pullRequestUrl = sources.goalPullRequestUrl ?: latestCycleUrl,
        pullRequestNumber = cycles.lastOrNull { it.pullRequestNumber != null }?.pullRequestNumber,
      )
  }
  if (cycles.isNotEmpty()) {
    val completedWithoutFinalCycle = sources.goalCompleted && cycles.last().finishedAt == null
    if (completedWithoutFinalCycle) {
      durableRead.recordDegradedRead(
        "goal-status.monitor_ci_terminal_result",
        "finished_monitor_ci_cycle",
        "goal_completed_with_last_observation",
      )
    }
    cards += monitorCard(cycles, completedWithoutFinalCycle)
    monitorFixCard(cycles, currentStep, workflowStatus, childWorkflowId)?.let(cards::add)
  }
  return cards
}

private fun currentPullRequestState(
  currentStep: String?,
  workflowStatus: WorkflowStatus?,
  childWorkflowId: String?,
  goalCompleted: Boolean,
): GoalRunnerPhaseCardState? {
  if (
    goalCompleted || childWorkflowId != null ||
    currentStep != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR
  ) {
    return null
  }
  return when (workflowStatus) {
    WorkflowStatus.RUNNING -> GoalRunnerPhaseCardState.RUNNING
    WorkflowStatus.BLOCKED -> GoalRunnerPhaseCardState.BLOCKED
    WorkflowStatus.PAUSED -> GoalRunnerPhaseCardState.PAUSED
    WorkflowStatus.FAILED, WorkflowStatus.TIMED_OUT -> GoalRunnerPhaseCardState.FAILED
    WorkflowStatus.PENDING, WorkflowStatus.COMPLETED, WorkflowStatus.ABANDONED, null -> null
  }
}

private fun GoalRunnerAssessmentResult.toAssessmentCard(): GoalRunnerPhaseCard? {
  val cardState =
    when (state) {
      GoalRunnerAssessmentState.RUNNING -> GoalRunnerPhaseCardState.RUNNING
      GoalRunnerAssessmentState.COMPLETED -> GoalRunnerPhaseCardState.COMPLETED
      GoalRunnerAssessmentState.BLOCKED -> GoalRunnerPhaseCardState.BLOCKED
      GoalRunnerAssessmentState.SKIPPED -> GoalRunnerPhaseCardState.SKIPPED
      GoalRunnerAssessmentState.PENDING -> if (round > 0) GoalRunnerPhaseCardState.PAUSED else null
    } ?: return null
  return GoalRunnerPhaseCard(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT,
    cardState,
    round,
    fixCommitSha = fixCommitSha,
  )
}

private fun qualityCards(
  quality: GoalQualityExecutionRead?,
  durableRead: GoalRunnerStatusDurableReadTracker,
): List<GoalRunnerPhaseCard> {
  val progress = (quality as? GoalQualityExecutionRead.Current)?.record?.execution?.progress ?: return emptyList()
  val accepted = progress.qa.acceptedEnvelopes + progress.validation.acceptedEnvelopes
  return listOf(progress.qa, progress.validation).flatMap { it.phaseRecords.values }
    .filter { it.phaseId in QUALITY_PHASE_IDS }
    .groupBy { it.phaseId }.values.mapNotNull { records -> records.maxByOrNull { it.attemptCount } }
    .sortedBy { record -> QUALITY_PHASE_IDS.indexOf(record.phaseId) }
    .mapNotNull { record ->
      record.toPhaseCardHeader()?.let { card ->
        val envelope = accepted.firstOrNull { it.phaseId == record.phaseId && it.attempt == record.attemptCount }
        val output =
          envelope?.let { acceptedEnvelope ->
            catchingNonCancellation { phaseCardOutput(acceptedEnvelope.envelope, record.phaseId) }.getOrElse { error ->
              durableRead.recordDegradedRead(
                "goal-status.goal_quality_card_output",
                "${record.phaseId}_accepted_envelope",
                "omitted",
                error,
              )
              null
            }
          }
        card.copy(output = output)
      }
    }
}

private fun monitorCard(
  cycles: List<MonitorCiCycleRecord>,
  goalCompleted: Boolean,
): GoalRunnerPhaseCard {
  val latest = cycles.last()
  val state =
    if (latest.finishedAt == null) {
      if (goalCompleted) GoalRunnerPhaseCardState.COMPLETED else GoalRunnerPhaseCardState.RUNNING
    } else {
      when (latest.outcome) {
        MonitorCiCycleOutcome.BLOCKED, MonitorCiCycleOutcome.UNAVAILABLE -> GoalRunnerPhaseCardState.BLOCKED
        MonitorCiCycleOutcome.PASSED, MonitorCiCycleOutcome.MERGED, MonitorCiCycleOutcome.NO_CHECKS,
        MonitorCiCycleOutcome.FAILED, MonitorCiCycleOutcome.CONFLICTED,
        MonitorCiCycleOutcome.NO_PULL_REQUEST,
        -> GoalRunnerPhaseCardState.COMPLETED
        null -> GoalRunnerPhaseCardState.BLOCKED
      }
    }
  val mapped =
    cycles.map { cycle ->
      GoalRunnerCiCycle(
        cycle.cycle,
        cycle.startedAt,
        cycle.observedAt,
        cycle.finishedAt,
        cycle.outcome?.wireValue,
        cycle.headSha,
        cycle.checks.map(MonitorCiCycleCheck::toGoalRunnerCiCheck),
      )
    }
  return GoalRunnerPhaseCard(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
    state,
    latest.cycle,
    cycles.first().startedAt,
    ciCycles = mapped,
  )
}

private fun monitorFixCard(
  cycles: List<MonitorCiCycleRecord>,
  currentStep: String?,
  workflowStatus: WorkflowStatus?,
  childWorkflowId: String?,
): GoalRunnerPhaseCard? {
  val routingIndex =
    cycles.indexOfLast { it.outcome == MonitorCiCycleOutcome.FAILED || it.outcome == MonitorCiCycleOutcome.CONFLICTED }
  if (routingIndex < 0) return null
  val routing = cycles[routingIndex]
  val laterCycles = cycles.drop(routingIndex + 1)
  val nextCycle = laterCycles.firstOrNull { it.cycle == routing.cycle + 1 }
  val state =
    when {
      laterCycles.isNotEmpty() -> GoalRunnerPhaseCardState.COMPLETED
      childWorkflowId == null && currentStep == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX ->
        if (workflowStatus == WorkflowStatus.BLOCKED) {
          GoalRunnerPhaseCardState.BLOCKED
        } else {
          GoalRunnerPhaseCardState.RUNNING
        }
      else -> GoalRunnerPhaseCardState.COMPLETED
    }
  return GoalRunnerPhaseCard(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
    state,
    repairedChecks =
      if (routing.outcome == MonitorCiCycleOutcome.CONFLICTED) {
        emptyList()
      } else {
        routing.checks.filter { it.bucket == MonitorCiCheckBucket.FAIL }.map(MonitorCiCycleCheck::toGoalRunnerCiCheck)
      },
    conflictBaseBranch = routing.conflictBaseBranch.takeIf { routing.outcome == MonitorCiCycleOutcome.CONFLICTED },
    fixCommitSha = nextCycle?.repairCommitSha,
  )
}

private fun MonitorCiCycleCheck.toGoalRunnerCiCheck(): GoalRunnerCiCheck {
  val start = startedAt
  val finish = completedAt
  val duration =
    if (start != null && finish != null && !finish.isBefore(start)) {
      Duration.between(start, finish).toMillis()
    } else {
      null
    }
  return GoalRunnerCiCheck(name, bucket.wireValue, link, startedAt, completedAt, duration)
}
