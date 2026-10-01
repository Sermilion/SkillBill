package skillbill.review.context.model.accounting

enum class ReviewAccountingTerminalOutcome(val wireValue: String) {
  COMPLETED("completed"),
  INCOMPLETE("incomplete"),
  PARTIAL_FAILURE("partial_failure"),
  SKIPPED_NOT_APPLICABLE("skipped_not_applicable"),
  REVIEW_CONTEXT_BUDGET_EXCEEDED("review_context_budget_exceeded"),
  FAILED("failed"),
  TIMEOUT("timeout"),
  INTERRUPTED("interrupted"),
  SPAWN_FAILURE("spawn_failure"),
  PROCESS_FAILURE("process_failure"),
  UNSUPPORTED_PROVIDER("unsupported_provider"),
  NO_OP_RESUME("no_op_resume"),
  ;

  companion object {
    fun fromWire(value: String): ReviewAccountingTerminalOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class ReviewBudgetKind(val wireValue: String) {
  PARENT_PACKET_BYTES("parent_packet_bytes"),
  LANE_LAUNCH_BYTES("lane_launch_bytes"),
  LANE_EVIDENCE_BYTES("lane_evidence_bytes"),
  EVIDENCE_RESULT_BYTES("evidence_result_bytes"),
  LANE_RESULT_BYTES("lane_result_bytes"),
  ASSIGNMENT_EXPANSIONS("assignment_expansions"),
  SPECIALIST_TOOL_CALLS("specialist_tool_calls"),
  SPECIALIST_MODEL_TURNS("specialist_model_turns"),
  ROUTING_ANALYSIS_PAIRS("routing_analysis_pairs"),
  ROUTING_ANALYSIS_BYTES("routing_analysis_bytes"),
  SPEC_INTENT_PROJECTION("spec_intent_projection"),
  LANE_RUN_OUTCOME("lane_run_outcome"),
  ;

  companion object {
    fun fromWire(value: String): ReviewBudgetKind? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class ReviewIntegrationTerminalOutcome {
  COMPLETED,
  SKIPPED_NOT_APPLICABLE,
  REVIEW_CONTEXT_BUDGET_EXCEEDED,
  FAILED,
  TIMEOUT,
  INTERRUPTED,
  SPAWN_FAILURE,
  PROCESS_FAILURE,
  UNSUPPORTED_PROVIDER,
  NO_OP_RESUME,
  ;

  val wireValue: String get() = name.lowercase()

  val isDurablyComplete: Boolean
    get() = this == COMPLETED || this == SKIPPED_NOT_APPLICABLE || this == NO_OP_RESUME

  companion object {
    fun fromWire(value: String): ReviewIntegrationTerminalOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}
