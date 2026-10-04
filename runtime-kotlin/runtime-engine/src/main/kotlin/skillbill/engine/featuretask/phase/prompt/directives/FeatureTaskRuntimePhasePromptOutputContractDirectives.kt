package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget

fun minimalSettlementContract(
  stepName: String,
  target: FeatureTaskRuntimePhaseSettlementTarget?,
  valueContent: String,
): String {
  val settlement = settlementDirective(stepName, target)
  return listOf(settlement, valueContentSection(valueContent), proseFallback(fallback = settlement.isNotEmpty()))
    .filter(String::isNotEmpty)
    .joinToString(separator = "\n\n")
}

private fun valueContentSection(valueContent: String): String =
  if (valueContent.isBlank()) "" else "## Value content\n" + valueContent.trim()

private fun proseFallback(fallback: Boolean): String {
  val heading =
    if (fallback) {
      "## Fallback final output (only when the settlement tools are unavailable)"
    } else {
      "## Required final output"
    }
  return """
    $heading
    End your response with plain prose carrying everything the next phase needs (what this phase
    produced, deviations from the briefing, what was deliberately left to later phases), or exactly
    what a value content section defines. The runtime records that prose as the phase value as
    written. Do not wrap it in a status or JSON envelope. Report an obstacle only when a concrete one
    stopped the phase, and name that obstacle in the prose.
    """.trimIndent()
}
