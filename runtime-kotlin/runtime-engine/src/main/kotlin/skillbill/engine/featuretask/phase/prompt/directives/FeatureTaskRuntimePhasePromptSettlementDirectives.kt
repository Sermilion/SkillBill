package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget

const val PHASE_COMPLETE_SETTLEMENT_TOOL: String = "mcp__skill-bill__feature_task_phase_complete"
const val PHASE_BLOCK_SETTLEMENT_TOOL: String = "mcp__skill-bill__feature_task_phase_block"

fun settlementDirective(
  stepName: String,
  target: FeatureTaskRuntimePhaseSettlementTarget?,
): String {
  if (target == null) return ""
  return """
    ## Required final output (durable settlement)
    Finish this phase through the skill-bill MCP settlement tools, not by printing a JSON envelope.
    The tools may be deferred in this session: load them first with ToolSearch
    `select:$PHASE_COMPLETE_SETTLEMENT_TOOL,$PHASE_BLOCK_SETTLEMENT_TOOL`.
    - Finished: call `$PHASE_COMPLETE_SETTLEMENT_TOOL` with workflow_id "${target.workflowId}",
      phase_id "$stepName", attempt ${target.attempt}, and value: one prose string carrying everything
      the next phase needs (what this phase produced, deviations from the briefing, what was
      deliberately left to later phases). When a value content section follows, it defines value and
      overrides this description: carry its content as prose or as JSON text, whichever is clearer, and
      emit any exact literal it names with nothing added. Otherwise the runtime does not validate the
      shape of value. Optional summary: one sentence.
    - Cannot finish because of a concrete obstacle: call `$PHASE_BLOCK_SETTLEMENT_TOOL` with the same
      workflow_id, phase_id, and attempt, a reason string naming the obstacle, and failure_disposition
      one of "retryable",
      "non_retryable_policy_conflict", "needs_user_action", "process_failure", or "invalid_output".
    Copy workflow_id, phase_id, and attempt exactly as written here. After the tool returns status
    "ok", end your response with a short prose recap and nothing structured. Use the prose
    fallback below only if both tools stay unavailable after loading them.
    """.trimIndent()
}
