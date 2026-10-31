package skillbill.engine.goalrunner.qa

import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest

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
}

public enum class GoalQaSkipReason(internal val wireValue: String) {
  UNSUPPORTED_TARGET("unsupported_target"),
  SUBTASKS_ALREADY_RAN_QA("subtasks_already_ran_qa"),
  ALREADY_SETTLED_ON_TREE("already_settled_on_tree"),
  ;

  internal companion object {
    internal fun fromWire(value: String?): GoalQaSkipReason? = entries.firstOrNull { it.wireValue == value }
  }
}
