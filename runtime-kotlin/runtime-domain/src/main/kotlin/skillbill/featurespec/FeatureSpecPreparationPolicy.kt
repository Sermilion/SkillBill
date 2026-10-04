package skillbill.featurespec

import skillbill.error.core.invalidFeatureSpecPreparationRequest
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationIntake
import skillbill.featurespec.model.FeatureSpecPreparationMode

object FeatureSpecPreparationPolicy {
  private val decomposedHints =
    listOf(
      "decompose",
      "decomposed",
      "subtask",
      "subtasks",
      "resumable",
      "milestone",
      "milestones",
      "multi-step",
      "multi step",
    )

  fun prepare(intake: FeatureSpecPreparationIntake): FeatureSpecPreparationDecision {
    val issueKey = intake.issueKey.trim()
    if (issueKey.isBlank()) {
      invalidRequest("issue_key", "issue key is required.")
    }

    val intendedOutcome = intake.intendedOutcome.trim()
    if (intendedOutcome.isBlank()) {
      invalidRequest("intended_outcome", "intended outcome is required.")
    }

    val acceptanceCriteria =
      normalizeRequiredList(
        values = intake.acceptanceCriteria,
        fieldPath = "acceptance_criteria",
        emptyReason = "at least one acceptance criterion is required.",
      )
    val constraints =
      normalizeRequiredList(
        values = intake.constraints,
        fieldPath = "constraints",
        emptyReason = "at least one constraint is required.",
      )
    val nonGoals = normalizeOptionalList(intake.nonGoals, "non_goals")

    return FeatureSpecPreparationDecision(
      issueKey = issueKey,
      intendedOutcome = intendedOutcome,
      acceptanceCriteria = acceptanceCriteria,
      constraints = constraints,
      nonGoals = nonGoals,
      mode = classifyMode(intendedOutcome, acceptanceCriteria, constraints),
    )
  }

  private fun classifyMode(
    intendedOutcome: String,
    acceptanceCriteria: List<String>,
    constraints: List<String>,
  ): FeatureSpecPreparationMode {
    val explicit = FeatureSpecPreparationMode.fromWireValue(intendedOutcome.lowercase())
    if (explicit != null) {
      return explicit
    }
    val flattenedSignals =
      buildString {
        append(intendedOutcome.lowercase())
        append(' ')
        append(acceptanceCriteria.joinToString(" ").lowercase())
        append(' ')
        append(constraints.joinToString(" ").lowercase())
      }
    return if (decomposedHints.any { hint -> flattenedSignals.contains(hint) }) {
      FeatureSpecPreparationMode.DECOMPOSED
    } else {
      FeatureSpecPreparationMode.SINGLE_SPEC
    }
  }

  private fun normalizeRequiredList(
    values: List<String>,
    fieldPath: String,
    emptyReason: String,
  ): List<String> {
    if (values.isEmpty()) {
      invalidRequest(fieldPath, emptyReason)
    }
    return values.mapIndexed { index, value ->
      value.trim().takeIf(String::isNotBlank)
        ?: invalidRequest("$fieldPath[$index]", "value must be non-blank.")
    }
  }

  private fun normalizeOptionalList(
    values: List<String>,
    fieldPath: String,
  ): List<String> =
    values.mapIndexed { index, value ->
      value.trim().takeIf(String::isNotBlank)
        ?: invalidRequest("$fieldPath[$index]", "value must be non-blank.")
    }

  private fun invalidRequest(
    fieldPath: String,
    reason: String,
  ): Nothing = throw invalidFeatureSpecPreparationRequest(fieldPath = fieldPath, reason = reason)
}
