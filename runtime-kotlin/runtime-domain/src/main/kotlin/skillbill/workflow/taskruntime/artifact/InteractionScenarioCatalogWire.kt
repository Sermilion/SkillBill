package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidInteractionScenarioCatalog
import skillbill.review.spec.InteractionScenarioCatalogShape
import skillbill.review.spec.model.InteractionScenario
import skillbill.review.spec.model.InteractionScenarioCatalog
import skillbill.review.spec.model.TransitionalRegression
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.math.BigDecimal
import skillbill.contracts.workflow.featuretask.InteractionScenarioCatalogPayloadKeys as Keys

fun InteractionScenarioCatalog.asInteractionScenarioCatalogWireMap(): FeatureTaskRuntimeWorkflowArtifactMap {
  InteractionScenarioCatalogShape.issues(this).firstOrNull()?.let { issue ->
    throw invalidInteractionScenarioCatalog(ENCODE_LABEL, "${issue.fieldPath}: ${issue.reason}")
  }
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      Keys.CONTRACT_VERSION to INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION,
      Keys.SPEC_DIGEST to specDigest,
      Keys.SCENARIOS to scenarios.map(::scenarioWire),
      Keys.TRANSITIONAL_REGRESSIONS to transitionalRegressions.map(::regressionWire),
    ),
  )
}

fun FeatureTaskRuntimeWorkflowArtifactMap.toInteractionScenarioCatalog(
  sourceLabel: String,
): InteractionScenarioCatalog = InteractionScenarioCatalogDecoder(sourceLabel).decode(this)

private const val ENCODE_LABEL = "interaction-scenario-catalog"

private fun scenarioWire(scenario: InteractionScenario): Map<String, Any?> =
  mapOf(
    Keys.SCENARIO_REF to scenario.scenarioRef,
    Keys.CRITERION_REFS to scenario.criterionRefs,
    Keys.SUBTASK_REFS to scenario.subtaskRefs,
    Keys.ENTRY_POINT to scenario.entryPoint,
    Keys.PRECONDITIONS to scenario.preconditions,
    Keys.STEPS to scenario.steps,
    Keys.OUTCOME to scenario.outcome,
  )

private fun regressionWire(regression: TransitionalRegression): Map<String, Any?> =
  mapOf(
    Keys.REGRESSION_REF to regression.regressionRef,
    Keys.BEHAVIOR to regression.behavior,
    Keys.DISABLED_BY_SUBTASK to regression.disabledBySubtask,
    Keys.RESTORED_BY_SUBTASK to regression.restoredBySubtask,
  )

private class InteractionScenarioCatalogDecoder(private val sourceLabel: String) {
  fun decode(raw: FeatureTaskRuntimeWorkflowArtifactMap): InteractionScenarioCatalog {
    if (!raw.isObject) fail(ROOT, "must be an object.")
    val root = objectOf(raw, ROOT, TOP_LEVEL_KEYS)
    if (root[Keys.CONTRACT_VERSION] != INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION) {
      fail(Keys.CONTRACT_VERSION, "must be $INTERACTION_SCENARIO_CATALOG_CONTRACT_VERSION.")
    }
    val catalog =
      InteractionScenarioCatalog(
        specDigest = text(root[Keys.SPEC_DIGEST], Keys.SPEC_DIGEST),
        scenarios =
          list(root[Keys.SCENARIOS], Keys.SCENARIOS).mapIndexed { index, value ->
            scenario(value, "${Keys.SCENARIOS}[$index]")
          },
        transitionalRegressions =
          list(root[Keys.TRANSITIONAL_REGRESSIONS], Keys.TRANSITIONAL_REGRESSIONS).mapIndexed { index, value ->
            regression(value, "${Keys.TRANSITIONAL_REGRESSIONS}[$index]")
          },
      )
    InteractionScenarioCatalogShape.issues(catalog).firstOrNull()?.let { fail(it.fieldPath, it.reason) }
    return catalog
  }

  private fun scenario(
    value: Any?,
    path: String,
  ): InteractionScenario {
    val fields = objectOf(value, path, SCENARIO_KEYS)
    return InteractionScenario(
      scenarioRef = text(fields[Keys.SCENARIO_REF], "$path.${Keys.SCENARIO_REF}"),
      criterionRefs =
        list(fields[Keys.CRITERION_REFS], "$path.${Keys.CRITERION_REFS}").map {
          text(it, "$path.${Keys.CRITERION_REFS}")
        },
      subtaskRefs =
        list(fields[Keys.SUBTASK_REFS], "$path.${Keys.SUBTASK_REFS}").map {
          integer(it, "$path.${Keys.SUBTASK_REFS}")
        },
      entryPoint = text(fields[Keys.ENTRY_POINT], "$path.${Keys.ENTRY_POINT}"),
      preconditions = text(fields[Keys.PRECONDITIONS], "$path.${Keys.PRECONDITIONS}"),
      steps = text(fields[Keys.STEPS], "$path.${Keys.STEPS}"),
      outcome = text(fields[Keys.OUTCOME], "$path.${Keys.OUTCOME}"),
    )
  }

  private fun regression(
    value: Any?,
    path: String,
  ): TransitionalRegression {
    val fields = objectOf(value, path, REGRESSION_KEYS)
    return TransitionalRegression(
      regressionRef = text(fields[Keys.REGRESSION_REF], "$path.${Keys.REGRESSION_REF}"),
      behavior = text(fields[Keys.BEHAVIOR], "$path.${Keys.BEHAVIOR}"),
      disabledBySubtask = integer(fields[Keys.DISABLED_BY_SUBTASK], "$path.${Keys.DISABLED_BY_SUBTASK}"),
      restoredBySubtask = integer(fields[Keys.RESTORED_BY_SUBTASK], "$path.${Keys.RESTORED_BY_SUBTASK}"),
    )
  }

  private fun objectOf(
    value: Any?,
    path: String,
    keys: Set<String>,
  ): Map<*, *> {
    val map = value as? Map<*, *> ?: fail(path, "must be an object.")
    val unknown = map.keys.filterNot { it is String && it in keys }
    val missing = keys.filterNot { map.containsKey(it) }
    if (unknown.isNotEmpty() || missing.isNotEmpty()) {
      fail(path, "has unknown fields $unknown or missing fields $missing.")
    }
    return map
  }

  private fun list(
    value: Any?,
    path: String,
  ): List<*> = value as? List<*> ?: fail(path, "must be an array.")

  private fun text(
    value: Any?,
    path: String,
  ): String = value as? String ?: fail(path, "must be a string.")

  private fun integer(
    value: Any?,
    path: String,
  ): Int = (value as? Number)?.toExactInt() ?: fail(path, "must be an integer.")

  private fun fail(
    path: String,
    reason: String,
  ): Nothing = throw invalidInteractionScenarioCatalog(sourceLabel, "$path: $reason")

  private companion object {
    const val ROOT = "<root>"
    val TOP_LEVEL_KEYS = setOf(Keys.CONTRACT_VERSION, Keys.SPEC_DIGEST, Keys.SCENARIOS, Keys.TRANSITIONAL_REGRESSIONS)
    val SCENARIO_KEYS =
      setOf(
        Keys.SCENARIO_REF,
        Keys.CRITERION_REFS,
        Keys.SUBTASK_REFS,
        Keys.ENTRY_POINT,
        Keys.PRECONDITIONS,
        Keys.STEPS,
        Keys.OUTCOME,
      )
    val REGRESSION_KEYS =
      setOf(Keys.REGRESSION_REF, Keys.BEHAVIOR, Keys.DISABLED_BY_SUBTASK, Keys.RESTORED_BY_SUBTASK)
  }
}

internal fun Number.toExactInt(): Int? =
  when (this) {
    is Byte, is Short, is Int -> toInt()
    else ->
      try {
        BigDecimal(toString()).intValueExact()
      } catch (_: ArithmeticException) {
        null
      } catch (_: NumberFormatException) {
        null
      }
  }
