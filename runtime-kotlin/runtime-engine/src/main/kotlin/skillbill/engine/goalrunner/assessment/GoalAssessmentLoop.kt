package skillbill.engine.goalrunner.assessment

import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.goalrunner.assessment.GoalAssessmentLoopRules
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord

internal class GoalAssessmentLoop(
  private val services: GoalAssessmentRunServices,
  private val fixStep: GoalAssessmentFixStep,
  private val assessRound: (GoalRunnerManifestState, GoalRunnerRunRequest, Int) -> GoalAssessmentRoundResult,
) {
  fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentRecord {
    val record =
      stored(state) ?: return GoalAssessmentRecord(issueKey = state.manifest.issueKey, exitReason = UNAVAILABLE)
    return if (record.exitReason != null) record else drive(state, request, record)
  }

  private fun drive(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    stored: GoalAssessmentRecord,
  ): GoalAssessmentRecord {
    var record = stored
    for (roundNumber in record.rounds.size.coerceAtLeast(1)..GoalAssessmentRecord.MAX_ROUNDS) {
      val step = round(state, request, record, roundNumber)
      record = step.record
      step.exit?.let { return exit(state, record, it) }
    }
    return exit(state, record, GoalAssessmentExitReason.ROUND_CAP)
  }

  private fun round(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    record: GoalAssessmentRecord,
    roundNumber: Int,
  ): RoundStep {
    if (record.rounds.getOrNull(roundNumber - 1)?.fix != null) {
      return decided(state, request, record, roundNumber, null)
    }
    return when (val result = assessRound(state, request, roundNumber)) {
      is GoalAssessmentRoundResult.Unavailable ->
        RoundStep(result.record.takeIf { it.rounds.size >= record.rounds.size } ?: record, UNAVAILABLE)
      is GoalAssessmentRoundResult.Assessed -> decided(state, request, result.record, roundNumber, result.fixSet)
    }
  }

  private fun decided(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    record: GoalAssessmentRecord,
    roundNumber: Int,
    fixSet: GoalAssessmentFixSet?,
  ): RoundStep {
    val decision = GoalAssessmentLoopRules.decide(record, roundNumber)
    return when {
      !decision.fix -> RoundStep(record, decision.exit)
      fixSet?.isEmpty == true -> RoundStep(record, UNAVAILABLE)
      else -> RoundStep(fixStep.run(state, request, record, roundNumber, fixSet ?: NO_FIX_SET), decision.exit)
    }
  }

  fun skipNoChanges(state: GoalRunnerManifestState) {
    val record = stored(state) ?: return
    if (record.exitReason != null) return
    services.warn("seam=goal_assessment value_expected=assessment value_used=skipped reason=no_changes")
    services.persist(state, record.copy(exitReason = GoalAssessmentExitReason.SKIPPED_NO_CHANGES))
  }

  private fun stored(state: GoalRunnerManifestState): GoalAssessmentRecord? =
    when (val read = services.outcomeStore.readGoalAssessmentRecord(state.parentWorkflowId)) {
      GoalAssessmentRecordRead.Absent -> GoalAssessmentRecord(issueKey = state.manifest.issueKey)
      is GoalAssessmentRecordRead.Recorded -> read.record
      is GoalAssessmentRecordRead.Corrupt -> {
        services.warn("the stored assessment record is unreadable, so the stage is skipped: ${read.reason}")
        null
      }
    }

  private fun exit(
    state: GoalRunnerManifestState,
    record: GoalAssessmentRecord,
    reason: GoalAssessmentExitReason,
  ): GoalAssessmentRecord {
    val exited =
      record.copy(
        exitReason = reason,
        openFindingRefs = GoalAssessmentLoopRules.openFindings(record).map { it.findingRef },
      )
    services.persist(state, exited)
    return exited
  }

  private class RoundStep(
    val record: GoalAssessmentRecord,
    val exit: GoalAssessmentExitReason?,
  )

  private companion object {
    val UNAVAILABLE = GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE
    val NO_FIX_SET = GoalAssessmentFixSet(emptyList(), emptyList())
  }
}
