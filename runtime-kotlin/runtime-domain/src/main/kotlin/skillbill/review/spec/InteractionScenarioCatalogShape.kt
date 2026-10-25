package skillbill.review.spec

import skillbill.review.spec.model.InteractionScenario
import skillbill.review.spec.model.InteractionScenarioCatalog
import skillbill.review.spec.model.InteractionScenarioIssue
import skillbill.review.spec.model.TransitionalRegression
import skillbill.contracts.workflow.featuretask.InteractionScenarioCatalogPayloadKeys as Keys

object InteractionScenarioCatalogShape {
  fun issues(catalog: InteractionScenarioCatalog): List<InteractionScenarioIssue> =
    buildList {
      if (!SPEC_DIGEST.matches(catalog.specDigest)) {
        add(InteractionScenarioIssue(Keys.SPEC_DIGEST, "must be 64 hexadecimal characters."))
      }
      if (catalog.scenarios.size > InteractionScenarioCatalog.MAX_SCENARIOS) {
        add(
          InteractionScenarioIssue(
            Keys.SCENARIOS,
            "at most ${InteractionScenarioCatalog.MAX_SCENARIOS} scenarios are allowed.",
          ),
        )
      }
      catalog.scenarios.forEachIndexed { index, scenario ->
        addAll(scenarioIssues("${Keys.SCENARIOS}[$index]", scenario))
      }
      catalog.transitionalRegressions.forEachIndexed { index, regression ->
        addAll(regressionIssues("${Keys.TRANSITIONAL_REGRESSIONS}[$index]", regression))
      }
    }

  private fun scenarioIssues(
    path: String,
    scenario: InteractionScenario,
  ): List<InteractionScenarioIssue> =
    buildList {
      if (!SCENARIO_REF.matches(scenario.scenarioRef)) {
        add(InteractionScenarioIssue("$path.${Keys.SCENARIO_REF}", "must match IS-NNN."))
      }
      if (scenario.criterionRefs.size < InteractionScenarioCatalog.MIN_CRITERION_REFS) {
        add(InteractionScenarioIssue("$path.${Keys.CRITERION_REFS}", "needs at least two criterion refs."))
      }
      if (scenario.criterionRefs.distinct().size != scenario.criterionRefs.size) {
        add(InteractionScenarioIssue("$path.${Keys.CRITERION_REFS}", "must not repeat a criterion ref."))
      }
      if (scenario.criterionRefs.any { !CRITERION_REF.matches(it) }) {
        add(InteractionScenarioIssue("$path.${Keys.CRITERION_REFS}", "every ref must match AC-NNN."))
      }
      if (scenario.subtaskRefs.distinct().size != scenario.subtaskRefs.size || scenario.subtaskRefs.any { it < 1 }) {
        add(InteractionScenarioIssue("$path.${Keys.SUBTASK_REFS}", "must be unique positive subtask ids."))
      }
      listOf(
        Keys.ENTRY_POINT to scenario.entryPoint,
        Keys.PRECONDITIONS to scenario.preconditions,
        Keys.STEPS to scenario.steps,
        Keys.OUTCOME to scenario.outcome,
      ).filter { (_, value) -> value.isBlank() }
        .forEach { (key, _) -> add(InteractionScenarioIssue("$path.$key", "must not be blank.")) }
    }

  private fun regressionIssues(
    path: String,
    regression: TransitionalRegression,
  ): List<InteractionScenarioIssue> =
    buildList {
      if (!REGRESSION_REF.matches(regression.regressionRef)) {
        add(InteractionScenarioIssue("$path.${Keys.REGRESSION_REF}", "must match TR-NNN."))
      }
      if (regression.behavior.isBlank()) {
        add(InteractionScenarioIssue("$path.${Keys.BEHAVIOR}", "must not be blank."))
      }
      if (regression.disabledBySubtask < 1) {
        add(InteractionScenarioIssue("$path.${Keys.DISABLED_BY_SUBTASK}", "must be a positive subtask id."))
      }
      if (regression.restoredBySubtask < 1) {
        add(InteractionScenarioIssue("$path.${Keys.RESTORED_BY_SUBTASK}", "must be a positive subtask id."))
      }
    }

  private val SPEC_DIGEST = Regex("[a-fA-F0-9]{64}")
  private val SCENARIO_REF = Regex("IS-[0-9]{3}")
  private val REGRESSION_REF = Regex("TR-[0-9]{3}")
  internal val CRITERION_REF = Regex("AC-[0-9]{3}")
}
