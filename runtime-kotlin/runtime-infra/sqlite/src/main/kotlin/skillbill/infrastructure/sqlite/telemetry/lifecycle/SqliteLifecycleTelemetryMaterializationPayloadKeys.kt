package skillbill.infrastructure.sqlite.telemetry.lifecycle

const val AUDIT_GAP_MEASUREMENT_GRAIN_PER_RUN: String = "audit_gap_rounds_per_run"

const val AGENT_CONTEXT_MEASUREMENT_GRAIN_DISTINCT_PER_RUN: String = "distinct_resolved_agents_per_run"

const val PHASE_STRATEGY_MEASUREMENT_GRAIN_ADMITTED_PER_RECORDED_PHASE: String =
  "admitted_strategy_per_recorded_phase"

object SqliteLifecycleTelemetryMaterializationPayloadKeys {
  const val FEATURE_SIZE: String = "feature_size"
  const val ORCHESTRATED: String = "orchestrated"
  const val DRY_RUN: String = "dry_run"
  const val PHASE_OUTCOMES: String = "phase_outcomes"
  const val REVIEW_FIX_ITERATION_COUNT: String = "review_fix_iteration_count"
  const val FINDING_VERIFICATION_VERIFIED_COUNT: String = "finding_verification_verified_count"
  const val FINDING_VERIFICATION_REJECTED_COUNT: String = "finding_verification_rejected_count"
  const val REGENERATION_ACTIVATION_COUNT: String = "regeneration_activation_count"
  const val REGENERATION_ATTEMPT_COUNT: String = "regeneration_attempt_count"
  const val REGENERATION_OUTCOME_COUNTS: String = "regeneration_outcome_counts"
  const val CRASH_RECONCILIATION_COUNT: String = "crash_reconciliation_count"
  const val LAST_INCOMPLETE_PHASE: String = "last_incomplete_phase"
  const val RESOLVED_BRANCH: String = "resolved_branch"
  const val ITEMS: String = "items"
  const val ACTUAL: String = "actual"
  const val EXPECTED: String = "expected"
  const val REASON: String = "reason"
  const val SEAM: String = "seam"
  const val LAUNCHED_AGENT_AVAILABILITY: String = "launched_agent_availability"
  const val FINAL_PR_BODY: String = "final_pr_body"
  const val GENERATED_DESCRIPTION: String = "generated_description"
}
