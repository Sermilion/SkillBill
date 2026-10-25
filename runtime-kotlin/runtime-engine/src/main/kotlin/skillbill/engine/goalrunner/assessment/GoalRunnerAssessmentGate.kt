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

  /**
   * Runs the whole assessment stage once for the goal in [state]: assess, verify, fix, validate and commit until an
   * exit rule fires, then returns the record carrying the exit reason. A recorded exit skips the stage. Never throws
   * on a stage failure: those degrade into a warning and the record's exit.
   */
  fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentRecord

  /** Records `skipped_no_changes` for a goal that finished without changes, unless an exit is already recorded. */
  fun skipNoChanges(state: GoalRunnerManifestState)
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
