package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState

data class AcceptanceCriterionUnknownScenario(
  val scenarioRef: String,
  val reason: String?,
)

private val UNKNOWN_LINE = Regex("""^Interaction (\S+): unknown trace (.+)$""", RegexOption.MULTILINE)

fun AcceptanceCriterionRepairState.unknownScenarios(): List<AcceptanceCriterionUnknownScenario> {
  val reasons =
    proseAudits.filter { it.criterionRef == null && it.id.endsWith(UNKNOWN_AUDIT_SUFFIX) }
      .flatMap { audit -> UNKNOWN_LINE.findAll(audit.prose).map { it.groupValues[1] to it.groupValues[2] }.toList() }
      .toMap()
  return interactionTrace.unknownScenarioRefs.map { AcceptanceCriterionUnknownScenario(it, reasons[it]) }
}
