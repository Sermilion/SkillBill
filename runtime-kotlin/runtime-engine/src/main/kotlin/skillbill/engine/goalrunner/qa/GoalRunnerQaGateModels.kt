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

enum class GoalQaSkipReason {
  SUBTASKS_ALREADY_RAN_QA,
  ALREADY_SETTLED_ON_TREE,
}
