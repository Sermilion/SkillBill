package skillbill.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionState
import skillbill.goalrunner.assessment.model.GoalAssessmentRoundDecision
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification

object GoalAssessmentLoopRules {
  const val PERSISTENT_FIX_ROUNDS: Int = 2

  fun openFindings(record: GoalAssessmentRecord): List<GoalAssessmentFinding> =
    record.rounds.flatMap { it.findings }.filter { it.state == GoalAssessmentFindingState.OPEN }

  fun decide(
    record: GoalAssessmentRecord,
    roundNumber: Int,
  ): GoalAssessmentRoundDecision {
    val confirmed =
      openFindings(record).filter {
        it.kind == GoalAssessmentFindingKind.DEFECT &&
          it.severity.blocksAdvance &&
          it.verification == GoalAssessmentVerification.CONFIRMED
      }
    val notRestored =
      record.rounds.getOrNull(roundNumber - 1)?.regressions.orEmpty()
        .any { it.state == GoalAssessmentRegressionState.NOT_RESTORED }
    return when {
      confirmed.any { it.originRound < roundNumber && it.fixRoundsSurvived >= PERSISTENT_FIX_ROUNDS } ->
        GoalAssessmentRoundDecision(fix = false, exit = GoalAssessmentExitReason.PERSISTENT_FINDING)
      confirmed.isEmpty() -> GoalAssessmentRoundDecision(fix = notRestored, exit = GoalAssessmentExitReason.CONVERGED)
      else ->
        GoalAssessmentRoundDecision(
          fix = true,
          exit = GoalAssessmentExitReason.ROUND_CAP.takeIf { roundNumber >= GoalAssessmentRecord.MAX_ROUNDS },
        )
    }
  }
}
