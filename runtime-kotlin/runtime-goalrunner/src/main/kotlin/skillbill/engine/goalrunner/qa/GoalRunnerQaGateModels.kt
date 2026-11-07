package skillbill.engine.goalrunner.qa

import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest

internal data class GoalQaSkipDecision(
  val reason: GoalQaSkipReason,
  val testedTreeIdentity: String? = null,
)

public fun interface GoalRunnerQaGate {
  public fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult
}

public sealed interface GoalQaGateResult {
  public data object Passed : GoalQaGateResult

  public data class Skipped(public val reason: GoalQaSkipReason) : GoalQaGateResult

  public data class Warned(public val reason: String) : GoalQaGateResult

  public data class Paused(
    public val reason: String,
    public val stepId: String,
  ) : GoalQaGateResult

  /**
   * The retained quality execution failed an integrity check during the [stepId] stage (build, qa or
   * validate), so the goal must block there instead of publishing.
   */
  public data class Blocked(
    public val reason: String,
    public val stepId: String,
    public val failureCode: String?,
  ) : GoalQaGateResult
}

public enum class GoalQaSkipReason(internal val wireValue: String) {
  DISABLED_POLICY("disabled_policy"),
  UNSUPPORTED_TARGET("unsupported_target"),
  SUBTASKS_ALREADY_RAN_QA("subtasks_already_ran_qa"),
  ALREADY_SETTLED_ON_TREE("already_settled_on_tree"),
  ;

  internal companion object {
    internal fun fromWire(value: String?): GoalQaSkipReason? = entries.firstOrNull { it.wireValue == value }
  }
}
