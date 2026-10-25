package skillbill.engine.goalrunner.assessment

import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord

/** Runs one cross-criterion assessment round over a finished goal and reports which findings may be fixed. */
interface GoalRunnerAssessmentGate {
  /**
   * Assesses [round] of the goal in [state] and returns the persisted record with the findings that hold fix
   * authority. Never throws on an assessor, verifier, parse or persistence failure: those degrade into the result.
   */
  fun runRound(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    round: Int,
  ): GoalAssessmentRoundResult
}

sealed interface GoalAssessmentRoundResult {
  val record: GoalAssessmentRecord

  data class Assessed(
    override val record: GoalAssessmentRecord,
    val fixSet: GoalAssessmentFixSet,
  ) : GoalAssessmentRoundResult

  data class Unavailable(
    override val record: GoalAssessmentRecord,
    val reason: String,
  ) : GoalAssessmentRoundResult
}
