package skillbill.engine.goal

public enum class GoalRunnerWedgeClass(public val wireValue: String, public val durableField: String) {
  MISSING_VALIDATION_DEPTH("missing_validation_depth", "validation_depth"),
  MISSING_QUALITY_GATE_SELECTION("missing_quality_gate_selection", "quality_gate_selection"),
  UNREACHABLE_REVIEW_BASE("unreachable_review_base", "review_base_sha"),
  UNREACHABLE_REMEDIATION_BASE("unreachable_remediation_base", "remediation_base_sha"),
  STALE_BLOCKED_CONTINUATION_OUTCOME("stale_blocked_continuation_outcome", "goal_continuation_outcome"),
  COMPLETED_UPSTREAM_MISSING_OUTPUT("completed_upstream_missing_output", "phase_output"),
  PHASE_OUTPUT_CONTRACT_INCOMPATIBLE("phase_output_contract_incompatible", "phase_output_contract_version"),
  STALE_EXECUTION_LEASE("stale_execution_lease", "execution_lease"),
  STALE_CHILD_WORKER_LEASE("stale_child_worker_lease", "worker_lease"),
  STALE_RUNNER_INTERRUPTED_PAUSE("stale_runner_interrupted_pause", "pause_state"),
  ;

  public companion object {
    public fun fromWire(value: String): GoalRunnerWedgeClass? = entries.firstOrNull { it.wireValue == value }
  }

  public val operatorRequired: Boolean
    get() = this == PHASE_OUTPUT_CONTRACT_INCOMPATIBLE
}

public data class GoalRunnerAppliedRepair(
  public val subtaskId: Int,
  public val workflowId: String,
  public val wedgeClass: GoalRunnerWedgeClass,
  public val field: String,
  public val priorValue: String?,
  public val newValue: String?,
)
