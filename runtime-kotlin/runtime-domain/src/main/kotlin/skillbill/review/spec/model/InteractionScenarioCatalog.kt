package skillbill.review.spec.model

data class InteractionScenario(
  val scenarioRef: String,
  val criterionRefs: List<String>,
  val subtaskRefs: List<Int>,
  val entryPoint: String,
  val preconditions: String,
  val steps: String,
  val outcome: String,
)

data class TransitionalRegression(
  val regressionRef: String,
  val behavior: String,
  val disabledBySubtask: Int,
  val restoredBySubtask: Int,
)

data class InteractionScenarioCatalog(
  val specDigest: String,
  val scenarios: List<InteractionScenario>,
  val transitionalRegressions: List<TransitionalRegression>,
) {
  companion object {
    const val MAX_SCENARIOS: Int = 6
    const val MIN_CRITERION_REFS: Int = 2
    const val MIN_PARENT_SUBTASK_REFS: Int = 2
  }
}

data class InteractionScenarioIssue(val fieldPath: String, val reason: String)

sealed interface InteractionScenarioSpecContext {
  data object Subtask : InteractionScenarioSpecContext

  data class Parent(val knownSubtaskIds: Set<Int>) : InteractionScenarioSpecContext
}

sealed interface InteractionScenarioParseResult {
  data class Parsed(
    val catalog: InteractionScenarioCatalog,
    val scenariosPresent: Boolean,
    val regressionsPresent: Boolean,
  ) : InteractionScenarioParseResult

  data class Rejected(val issues: List<InteractionScenarioIssue>) : InteractionScenarioParseResult
}
