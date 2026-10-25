package skillbill.engine.goalrunner

import skillbill.engine.goalrunner.assessment.GoalAssessmentRoundResult
import skillbill.engine.goalrunner.assessment.GoalRunnerAssessmentGate
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord

internal class RecordingGoalRunnerAssessmentGate(
  private val events: MutableList<String> = mutableListOf(),
) : GoalRunnerAssessmentGate {
  override fun runRound(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    round: Int,
  ): GoalAssessmentRoundResult = error("Finalization must run the whole stage, not a single round.")

  override fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentRecord {
    events += "assessment"
    return GoalAssessmentRecord(state.manifest.issueKey)
  }

  override fun skipNoChanges(state: GoalRunnerManifestState) {
    events += "assessment_skipped_no_changes"
  }
}
