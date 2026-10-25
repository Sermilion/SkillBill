package skillbill.engine.featuretask.prepare

import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.InteractionScenarioSectionParser
import skillbill.review.spec.model.InteractionScenarioIssue
import skillbill.review.spec.model.InteractionScenarioParseResult
import skillbill.review.spec.model.InteractionScenarioSpecContext
import skillbill.text.sha256HexUtf8
import skillbill.contracts.workflow.featuretask.InteractionScenarioCatalogPayloadKeys as Keys

internal object AuthoredInteractionScenarioCheck {
  private const val SUBTASK_SCENARIO_CRITERION_THRESHOLD = 2
  private const val TRANSITIONAL_BUNDLE_SUBTASK_THRESHOLD = 2

  fun requireSubtaskSections(
    index: Int,
    specText: String,
  ) {
    val fieldPath = "subtasks[$index].interaction_scenarios"
    when (val result = parse(specText, InteractionScenarioSpecContext.Subtask)) {
      is InteractionScenarioParseResult.Rejected -> reject(fieldPath, result.issues.first())
      is InteractionScenarioParseResult.Parsed ->
        if (!result.scenariosPresent && criterionCount(specText) >= SUBTASK_SCENARIO_CRITERION_THRESHOLD) {
          throw InvalidFeatureSpecPreparationRequestError(
            fieldPath = fieldPath,
            reason = "a subtask spec with two or more criteria needs an `## Interaction Scenarios` section.",
          )
        }
    }
  }

  fun checkParentSections(
    request: ParentSectionCheck,
    onWarning: (String) -> Unit,
  ) {
    val context = InteractionScenarioSpecContext.Parent(request.subtaskIds)
    val result = parse(request.specText, context)
    if (!request.rewritable) {
      protectedParentProblem(result)?.let { onWarning("${request.parentLabel} interaction sections ignored: $it") }
      return
    }
    when (result) {
      is InteractionScenarioParseResult.Rejected -> {
        val issue = result.issues.first()
        val fieldPath =
          if (issue.fieldPath.startsWith(Keys.TRANSITIONAL_REGRESSIONS)) {
            "parent.transitional_regressions"
          } else {
            "parent.interaction_scenarios"
          }
        reject(fieldPath, issue)
      }
      is InteractionScenarioParseResult.Parsed ->
        if (request.subtaskIds.size >= TRANSITIONAL_BUNDLE_SUBTASK_THRESHOLD && !result.regressionsPresent) {
          throw InvalidFeatureSpecPreparationRequestError(
            fieldPath = "parent.transitional_regressions",
            reason = "a parent of a bundle with two or more subtasks needs `## Transitional Regressions`.",
          )
        }
    }
  }

  private fun protectedParentProblem(result: InteractionScenarioParseResult): String? =
    when (result) {
      is InteractionScenarioParseResult.Rejected -> result.issues.first().let { "${it.fieldPath}: ${it.reason}" }
      is InteractionScenarioParseResult.Parsed ->
        "missing `## Interaction Scenarios` or `## Transitional Regressions`; treated as empty."
          .takeUnless { result.scenariosPresent && result.regressionsPresent }
    }

  private fun parse(
    specText: String,
    context: InteractionScenarioSpecContext,
  ): InteractionScenarioParseResult = InteractionScenarioSectionParser.parse(specText, sha256HexUtf8(specText), context)

  private fun criterionCount(specText: String): Int =
    GovernedSpecSectionParser.parseListSection(specText) {
      it.startsWith(GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX)
    }.size

  private fun reject(
    fieldPath: String,
    issue: InteractionScenarioIssue,
  ): Nothing =
    throw InvalidFeatureSpecPreparationRequestError(
      fieldPath = fieldPath,
      reason = "${issue.fieldPath}: ${issue.reason}",
    )
}

internal data class ParentSectionCheck(
  val specText: String,
  val parentLabel: String,
  val subtaskIds: Set<Int>,
  val rewritable: Boolean,
)
