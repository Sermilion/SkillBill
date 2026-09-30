package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget

fun minimalSettlementContract(
  stepName: String,
  target: FeatureTaskRuntimePhaseSettlementTarget?,
  valueContent: String,
): String {
  val settlement = settlementDirective(stepName, target)
  return listOf(settlement, valueContentSection(valueContent), minimalFinalObject(fallback = settlement.isNotEmpty()))
    .filter(String::isNotEmpty)
    .joinToString(separator = "\n\n")
}

private fun valueContentSection(valueContent: String): String =
  if (valueContent.isBlank()) "" else "## Value content\n" + valueContent.trim()

private fun minimalFinalObject(fallback: Boolean): String {
  val heading =
    if (fallback) {
      "## Fallback final output (only when the settlement tools are unavailable)"
    } else {
      "## Required final output"
    }
  return """
    $heading
    End your response with exactly one JSON object as the last thing you emit. Prefer a raw
    object with nothing after it; a single ```json fenced block is also accepted. The runtime
    stamps the contract version and phase id itself. Fields:
    - "status": one of "completed", "blocked", "failed"
    - "summary": one sentence describing what this phase did
    - "value": non-blank prose carrying everything the next phase needs
    - "verdict": optional; set it only when this briefing names a verdict for this phase
    - "failure_disposition": required when status is "blocked" or "failed"; one of "retryable",
      "non_retryable_policy_conflict", "needs_user_action", "process_failure", or "invalid_output".
      Omit it when status is "completed".
    """.trimIndent()
}

fun envelopeContract(
  stepName: String,
  producedOutputsAddendum: String,
  verdictContractLine: String,
): String =
  """
  ## Required final output (validated schema gate)
  End your response with exactly one JSON object as the last thing you emit. Prefer a raw
  object with nothing after it; a single ```json fenced block is also accepted. The runtime
  extracts that object and blocks the run if it does not validate against the phase-output
  contract. Copy these field values from this briefing; do not look them up in this checkout:
  - "contract_version": must be exactly "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION"
  - "phase_id": must be "$stepName"
  - "status": one of "completed", "blocked", "failed"
  - "failure_disposition": required by the runtime when status is "blocked" or "failed"; one of
    "retryable", "non_retryable_policy_conflict", "needs_user_action", "process_failure", or
    "invalid_output". Omit it when status is "completed".
  - "summary": non-empty string describing what this phase did
  - "produced_outputs": object. Empty {} is valid when this phase has no structured
    payload. When this briefing names a required shape below, that shape is required$producedOutputsAddendum
  - "derived_notes": optional; when present, a non-empty string of notes for downstream
    phases$verdictContractLine
  No top-level fields other than the ones listed above are allowed.
  """.trimIndent()
