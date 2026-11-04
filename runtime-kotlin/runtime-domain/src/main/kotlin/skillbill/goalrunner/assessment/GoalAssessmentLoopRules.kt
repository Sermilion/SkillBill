package skillbill.goalrunner.assessment

import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
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
    openFindings(record).map { it.findingRef } +
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
            it.round >= record.budgetStartRound &&
              it.targetRef == ref &&
              (
                it.fix?.let { fix ->
                  (
                    fix.status == GoalAssessmentFixStatus.NO_CHANGE &&
                      fix.validation == GoalAssessmentFixValidation.NOT_RUN &&
                      fix.commitSha == null &&
                      fix.changedFiles.isEmpty()
                  ) ||
                    (
                      fix.status == GoalAssessmentFixStatus.COMPLETED &&
                        fix.validation == GoalAssessmentFixValidation.PASSED &&
                        fix.commitSha != null
                    )
                } == true
              )
          },
        )
      }
    return FocusedRepairStrategy.next(targets, FocusedRepairStage.PLANNING)
  }

  fun publicationBlockReason(record: GoalAssessmentRecord): String? =
    when {
      record.exitReason != GoalAssessmentExitReason.CONVERGED ->
        "Goal assessment did not accept the goal: ${record.exitReason?.wireValue ?: "unfinished"}." +
          pendingRepairNote(record).orEmpty()
      pendingRefs(record).isNotEmpty() -> "Goal assessment has unresolved items: ${pendingRefs(record).joinToString()}."
      record.rounds.lastOrNull()?.fix != null -> "The last assessment repair still needs independent verification."
      else -> null
    }

  private fun pendingRepairNote(record: GoalAssessmentRecord): String? {
    if (record.exitReason != GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE) return null
    val round = record.rounds.lastOrNull() ?: return null
    val fix = round.fix?.takeIf { it.status == GoalAssessmentFixStatus.COMPLETED } ?: return null
    val repair = "The round ${round.round} repair${round.targetRef?.let { " of $it" }.orEmpty()}"
    val commit = fix.commitSha
    return when {
      commit == null && fix.validation == GoalAssessmentFixValidation.NOT_RUN ->
        " $repair changed ${filesText(fix.changedFiles)} and is not validated or committed yet. It resumes only " +
          "on the feature branch at assessed head ${round.headSha.take(SHORT_SHA)} with only those files changed."
      commit != null && fix.validation == GoalAssessmentFixValidation.PASSED ->
        " $repair was committed as ${commit.take(SHORT_SHA)}. It resumes only on the feature branch with that " +
          "commit as head."
      else -> null
    }
  }

  private fun filesText(files: List<String>): String {
    val listed = files.take(LISTED_REPAIR_FILES).joinToString()
    val more = files.size - LISTED_REPAIR_FILES
    return if (more > 0) "$listed and $more more" else listed
  }

  private const val SHORT_SHA = 12
  private const val LISTED_REPAIR_FILES = 5

  fun resetBudgetForOperatorResume(record: GoalAssessmentRecord): GoalAssessmentRecord =
    if (record.exitReason == GoalAssessmentExitReason.PERSISTENT_FINDING) {
      record.copy(exitReason = null, budgetStartRound = record.rounds.size.coerceAtLeast(1))
    } else {
      record
    }
}
