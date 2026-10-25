package skillbill.engine.goalrunner.status

import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.goalrunner.model.GoalRunnerAssessmentFinding
import skillbill.goalrunner.model.GoalRunnerAssessmentResult
import skillbill.goalrunner.model.GoalRunnerAssessmentState
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

private const val SEAM = "goal-status.goal_assessment_result"
private const val EXPECTED = "goal_assessment_record"

internal fun GoalRunnerWorkflowOutcomeStore.goalAssessmentResult(
  parentWorkflowId: String,
  durableRead: GoalRunnerStatusDurableReadTracker,
): GoalRunnerAssessmentResult? =
  runCatching { readGoalAssessmentRecord(parentWorkflowId) }.fold(
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
    exitReason != null -> GoalRunnerAssessmentState.COMPLETED
    rounds.isEmpty() -> GoalRunnerAssessmentState.PENDING
    else -> GoalRunnerAssessmentState.RUNNING
  }

private fun GoalAssessmentRecord.statusRound(): Int =
  when {
    exitReason != null || rounds.isEmpty() -> rounds.size
    rounds.last().fix != null -> (rounds.size + 1).coerceAtMost(GoalAssessmentRecord.MAX_ROUNDS)
    else -> rounds.size
  }
