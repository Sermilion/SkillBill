package skillbill.engine.goalrunner.qa

import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest

fun interface GoalRunnerQaGate {
  fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalQaGateResult
}

sealed interface GoalQaGateResult {
  data object Passed : GoalQaGateResult

  data class Skipped(val reason: GoalQaSkipReason) : GoalQaGateResult

  data class Warned(val reason: String) : GoalQaGateResult
}

enum class GoalQaSkipReason(val wireValue: String) {
  UNSUPPORTED_TARGET("unsupported_target"),
  SUBTASKS_ALREADY_RAN_QA("subtasks_already_ran_qa"),
  ALREADY_SETTLED_ON_TREE("already_settled_on_tree"),
  ;

  companion object {
    fun fromWire(value: String?): GoalQaSkipReason? = entries.firstOrNull { it.wireValue == value }
  }
}
