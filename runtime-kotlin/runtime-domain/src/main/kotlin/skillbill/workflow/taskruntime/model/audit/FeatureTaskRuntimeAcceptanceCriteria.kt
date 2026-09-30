package skillbill.workflow.taskruntime.model.audit

import skillbill.workflow.taskruntime.model.core.MAX_ACCEPTANCE_CRITERION_ORDINAL

fun canonicalAcceptanceCriterionRef(ordinal: Int): String {
  require(ordinal in 1..MAX_ACCEPTANCE_CRITERION_ORDINAL) {
    "Acceptance criterion ordinal must be 1-based and at most $MAX_ACCEPTANCE_CRITERION_ORDINAL, was $ordinal."
  }
  return "AC-" + ordinal.toString().padStart(ACCEPTANCE_CRITERION_REF_DIGITS, '0')
}

private const val ACCEPTANCE_CRITERION_REF_DIGITS: Int = 3

fun acceptanceCriterionIdentity(
  criterion: String,
  ordinal: Int,
): AcceptanceCriterionIdentity? {
  val label = ACCEPTANCE_CRITERION_LABEL.find(criterion.trim())?.value
  val canonical =
    if (label != null && !label.startsWith("S", ignoreCase = true)) {
      val number = label.substringAfter("AC", "").trimStart('-').toIntOrNull() ?: return null
      if (number !in 1..MAX_ACCEPTANCE_CRITERION_ORDINAL) return null
      canonicalAcceptanceCriterionRef(number)
    } else {
      canonicalAcceptanceCriterionRef(ordinal)
    }
  return AcceptanceCriterionIdentity(canonical, label?.uppercase())
}

data class AcceptanceCriterionIdentity(val canonicalRef: String, val originalLabel: String?) {
  fun identifiedText(criterion: String): String =
    if (originalLabel != null && !originalLabel.startsWith("S")) {
      canonicalRef + criterion.trim().substring(originalLabel.length)
    } else {
      "$canonicalRef. $criterion"
    }
}

private val ACCEPTANCE_CRITERION_LABEL =
  Regex("""^(?:S\d+-)?AC-?\d+(?=$|[.:\s/])""", RegexOption.IGNORE_CASE)
