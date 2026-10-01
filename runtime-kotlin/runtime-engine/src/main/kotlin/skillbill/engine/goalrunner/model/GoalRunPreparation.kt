package skillbill.engine.goalrunner.model

import skillbill.goalrunner.model.GoalRunnerRunReport

sealed interface GoalRunPreparation {
  data class Prepared(
    val state: GoalRunnerManifestState,
    val request: GoalRunnerRunRequest,
  ) : GoalRunPreparation

  data class PreparationBlocked(val report: GoalRunnerRunReport) : GoalRunPreparation
}
