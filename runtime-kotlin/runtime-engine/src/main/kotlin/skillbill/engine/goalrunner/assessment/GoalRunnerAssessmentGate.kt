package skillbill.engine.goalrunner.assessment

import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord

/** Runs one cross-criterion assessment round over a finished goal and reports which findings may be fixed. */
public interface GoalRunnerAssessmentGate {
  /**
   * Assesses [round] of the goal in [state] and returns the persisted record with the findings that hold fix
   * authority. Never throws on an assessor, verifier, parse or persistence failure: those degrade into the result.
   */
  public fun runRound(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    round: Int,
  ): GoalAssessmentRoundResult

  /**
   * Runs the whole assessment stage once for the goal in [state]: assess, verify, fix, validate and commit until an
   * exit rule fires, then returns the record carrying the exit reason. A recorded exit skips the stage. Never throws
   * on a stage failure: those degrade into a warning and the record's exit.
   */
  public fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalAssessmentRecord

  /** Records `skipped_no_changes` for a goal that finished without changes, unless an exit is already recorded. */
  public fun skipNoChanges(state: GoalRunnerManifestState): Unit
}

public sealed interface GoalAssessmentRoundResult {
  public val record: GoalAssessmentRecord

  public data class Assessed(
    override val record: GoalAssessmentRecord,
    internal val fixSet: GoalAssessmentFixSet,
  ) : GoalAssessmentRoundResult

  public data class Unavailable(
    override val record: GoalAssessmentRecord,
    public val reason: String,
  ) : GoalAssessmentRoundResult
}
