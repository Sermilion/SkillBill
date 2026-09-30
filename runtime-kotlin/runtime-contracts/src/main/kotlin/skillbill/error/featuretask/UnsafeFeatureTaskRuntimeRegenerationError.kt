package skillbill.error.featuretask

enum class FeatureTaskRuntimeRegenerationRefusal(val wireValue: String) {
  MISSING_WORKFLOW("missing_workflow"),
  TERMINAL_WORKFLOW("terminal_workflow"),
  UNPROVEN_GATE_SEMANTICS("unproven_gate_semantics"),
  IRREVERSIBLE_WORK_RECORDED("irreversible_work_recorded"),
  MISSING_PRODUCER_EVIDENCE("missing_producer_evidence"),
}

class UnsafeFeatureTaskRuntimeRegenerationError(
  val refusal: FeatureTaskRuntimeRegenerationRefusal,
) : IllegalStateException(
    "Receipt regeneration refused: ${refusal.wireValue}. " +
      "Retain the workflow and inspect its evidence with a compatible runtime.",
  )
