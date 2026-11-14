package skillbill.engine.goalrunner.status

import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalQualityExecutionPayloadKeys
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
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityReadiness
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
)

internal data class GoalFinishCardReadRequest(
  val parentWorkflowId: String,
  val assessment: GoalRunnerAssessmentResult?,
  val goalPullRequestUrl: String?,
  val currentStep: String?,
  val workflowStatus: WorkflowStatus?,
  val childWorkflowId: String?,
)

internal fun readGoalFinishCards(
  request: GoalFinishCardReadRequest,
  outcomeStore: GoalRunnerWorkflowOutcomeStore,
  database: DatabaseSessionFactory,
  durableRead: GoalRunnerStatusDurableReadTracker,
): List<GoalRunnerPhaseCard> {
  val quality =
    catchingNonCancellation { outcomeStore.retainedGoalQualityExecution(request.parentWorkflowId) }
      .getOrElse { error ->
        durableRead.recordDegradedRead(
          "goal-status.goal_finish_quality",
          "goal_quality_execution",
          "recovery_card",
          error,
        )
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
    GoalFinishCardSources(request.assessment, quality, cycles, request.goalPullRequestUrl),
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
  if (sources.goalPullRequestUrl != null || latestCycleUrl != null) {
    cards +=
      GoalRunnerPhaseCard(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        GoalRunnerPhaseCardState.COMPLETED,
        pullRequestUrl = sources.goalPullRequestUrl ?: latestCycleUrl,
        pullRequestNumber = cycles.lastOrNull { it.pullRequestNumber != null }?.pullRequestNumber,
      )
  }
  if (cycles.isNotEmpty()) {
    cards += monitorCard(cycles)
    monitorFixCard(cycles, currentStep, workflowStatus, childWorkflowId)?.let(cards::add)
  }
  return cards
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
  val progress = retainedQualityProgress(quality)
  if (progress == null) {
    if (qualityShowsNoCard(quality)) return emptyList()
    durableRead.recordDegradedRead(
      "goal-status.goal_finish_quality",
      "goal_quality_execution_progress",
      "recovery_card",
    )
    return listOf(qualityRecoveryCard(requireNotNull(quality)))
  }
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

private fun retainedQualityProgress(quality: GoalQualityExecutionRead?): GoalQualityExecutionProgress? =
  when (quality) {
    null -> null
    is GoalQualityExecutionRead.Current -> quality.record.execution?.progress
    is GoalQualityExecutionRead.Historical ->
      if (quality.hasAuthoritativePlansAndProgress) quality.progress else null
    is GoalQualityExecutionRead.Ambiguous -> null
    is GoalQualityExecutionRead.Invalid -> null
  }

private fun qualityShowsNoCard(quality: GoalQualityExecutionRead?): Boolean =
  quality == null ||
    quality is GoalQualityExecutionRead.Historical && quality.isCleanUnstarted() ||
    quality is GoalQualityExecutionRead.Current &&
    quality.record.readiness != GoalQualityReadiness.HISTORICAL_AMBIGUITY

private fun qualityRecoveryCard(quality: GoalQualityExecutionRead): GoalRunnerPhaseCard {
  val sourceVersion =
    when (quality) {
      is GoalQualityExecutionRead.Current -> quality.record.contractVersion
      is GoalQualityExecutionRead.Historical -> quality.sourceContractVersion
      is GoalQualityExecutionRead.Ambiguous -> quality.sourceContractVersion
      is GoalQualityExecutionRead.Invalid -> quality.sourceContractVersion ?: "unknown"
    }
  val displayedVersion = sourceVersion.takeIf { it in SUPPORTED_QUALITY_VERSIONS } ?: "unsupported"
  return GoalRunnerPhaseCard(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA,
    GoalRunnerPhaseCardState.BLOCKED,
    blockedReason =
      "INVALID_GOAL_QUALITY_EXECUTION: retained quality evidence from contract $displayedVersion " +
        "cannot establish accepted phase progress. Preserve the original and restore authoritative evidence.",
  )
}

private fun GoalQualityExecutionRead.Historical.isCleanUnstarted(): Boolean =
  !hasAuthoritativePlansAndProgress &&
    progress == null &&
    originalPayload[GoalQualityExecutionPayloadKeys.READINESS] == GoalQualityReadiness.CLEAN_UNSTARTED.wireValue

private val SUPPORTED_QUALITY_VERSIONS =
  setOf("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", GOAL_QUALITY_EXECUTION_CONTRACT_VERSION)

private fun monitorCard(cycles: List<MonitorCiCycleRecord>): GoalRunnerPhaseCard {
  val latest = cycles.last()
  val state =
    if (latest.finishedAt == null) {
      GoalRunnerPhaseCardState.RUNNING
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
