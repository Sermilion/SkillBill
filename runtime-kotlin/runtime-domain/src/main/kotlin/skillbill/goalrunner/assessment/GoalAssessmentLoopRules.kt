package skillbill.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRegressionState
import skillbill.goalrunner.assessment.model.GoalAssessmentScenarioVerdict
import skillbill.workflow.model.repair.FocusedRepairAction
import skillbill.workflow.model.repair.FocusedRepairStage
import skillbill.workflow.model.repair.FocusedRepairStrategy
import skillbill.workflow.model.repair.RepairTargetProgress

object GoalAssessmentLoopRules {
  fun openFindings(record: GoalAssessmentRecord): List<GoalAssessmentFinding> =
    record.rounds.flatMap { it.findings }.filter { it.state == GoalAssessmentFindingState.OPEN }

  fun pendingRefs(record: GoalAssessmentRecord): List<String> =
    openFindings(record).filter { it.kind == GoalAssessmentFindingKind.DEFECT }.map { it.findingRef } +
      record.rounds.flatMap { it.scenarios }.associateBy { it.scenarioRef }.values
        .filter { it.verdict == GoalAssessmentScenarioVerdict.BROKEN }.map { it.scenarioRef } +
      record.rounds.flatMap { it.regressions }.associateBy { it.regressionRef }.values
        .filter { it.state == GoalAssessmentRegressionState.NOT_RESTORED }.map { it.regressionRef }

  fun next(record: GoalAssessmentRecord): FocusedRepairAction {
    val targets =
      pendingRefs(record).map { ref ->
        RepairTargetProgress(
          ref,
          record.rounds.count {
            it.round >= record.budgetStartRound && it.targetRef == ref && it.fix != null
          },
        )
      }
    return FocusedRepairStrategy.next(targets, FocusedRepairStage.PLANNING)
  }

  fun publicationBlockReason(record: GoalAssessmentRecord): String? =
    when {
      record.exitReason != GoalAssessmentExitReason.CONVERGED ->
        "Goal assessment did not accept the goal: ${record.exitReason?.wireValue ?: "unfinished"}."
      pendingRefs(record).isNotEmpty() -> "Goal assessment has unresolved items: ${pendingRefs(record).joinToString()}."
      record.rounds.lastOrNull()?.fix != null -> "The last assessment repair still needs independent verification."
      else -> null
    }

  fun resetBudgetForOperatorResume(record: GoalAssessmentRecord): GoalAssessmentRecord =
    if (record.exitReason == GoalAssessmentExitReason.PERSISTENT_FINDING) {
      record.copy(exitReason = null, budgetStartRound = record.rounds.size.coerceAtLeast(1))
    } else {
      record
    }
}
