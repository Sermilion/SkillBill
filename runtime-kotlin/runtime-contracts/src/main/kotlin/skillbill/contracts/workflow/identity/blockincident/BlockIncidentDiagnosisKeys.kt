package skillbill.contracts.workflow.identity.blockincident

object BlockIncidentDiagnosisKeys {
  const val CERTAINTY_DIAGNOSED: String = "diagnosed"
  const val CERTAINTY_SUSPECTED: String = "suspected"
  const val CERTAINTY_UNKNOWN: String = "unknown"

  const val CATEGORY_PROJECT_SOURCE: String = "project_source"
  const val CATEGORY_USER_ENVIRONMENT: String = "user_environment"
  const val CATEGORY_EXTERNAL_SERVICE: String = "external_service"
  const val CATEGORY_SKILL_BILL_DEFECT: String = "skill_bill_defect"
  const val CATEGORY_UNRESOLVED: String = "unresolved"

  const val PREREQUISITE_CREDENTIALS_CONFIGURED: String = "credentials_configured"
  const val PREREQUISITE_CONFIGURATION_FIXED: String = "configuration_fixed"
  const val PREREQUISITE_PROJECT_EDIT_COMPLETED: String = "project_edit_completed"
  const val PREREQUISITE_EXTERNAL_SERVICE_AVAILABLE: String = "external_service_available"

  const val CHECK_RUNTIME_CHECKABLE: String = "runtime_checkable"
  const val CHECK_USER_CONFIRMED: String = "user_confirmed"

  const val ACTION_REOPEN_BLOCKED_PHASE: String = "reopen_blocked_phase"
  const val ACTION_RETRY_AFTER_PREREQUISITE: String = "retry_after_prerequisite"
  const val ACTION_NONE: String = "none"

  const val DEGRADATION_READ_ONLY_NOT_ENFORCED: String = "read_only_not_enforced"
  const val DEGRADATION_AGENT_UNAVAILABLE: String = "agent_unavailable"
  const val DEGRADATION_AGENT_TIMED_OUT: String = "agent_timed_out"
  const val DEGRADATION_AGENT_OUTPUT_REJECTED: String = "agent_output_rejected"
  const val DEGRADATION_EVIDENCE_UNAVAILABLE: String = "evidence_unavailable"
  const val DEGRADATION_EVIDENCE_TRUNCATED: String = "evidence_truncated"
  const val DEGRADATION_DEADLINE_EXCEEDED: String = "deadline_exceeded"
  const val DEGRADATION_NOT_PICKED_UP: String = "not_picked_up"
  const val DEGRADATION_CLAIM_TAKEN_OVER: String = "claim_taken_over"
  const val DEGRADATION_SPOOL_IMPORTED: String = "spool_imported"

  const val INVESTIGATOR_DETERMINISTIC: String = "deterministic"
  const val INVESTIGATOR_DETERMINISTIC_WITH_AGENT: String = "deterministic_with_agent"

  const val EVIDENCE_WORKFLOW_STATE: String = "workflow_state"
  const val EVIDENCE_PHASE_LEDGER: String = "phase_ledger"
  const val EVIDENCE_REJECTED_OUTPUT: String = "rejected_output"
  const val EVIDENCE_PRODUCER_OUTPUT: String = "producer_output"
  const val EVIDENCE_ATTEMPT_HISTORY: String = "attempt_history"
  const val EVIDENCE_RUNTIME_VERSION: String = "runtime_version"
  const val EVIDENCE_PHASE_BRIEFING: String = "phase_briefing"
  const val EVIDENCE_ORIGINAL_FAILURE: String = "original_failure"

  const val VIOLATION_DIAGNOSED_WITHOUT_EVIDENCE: String = "diagnosed_without_evidence"
  const val VIOLATION_UNRESOLVED_NOT_UNKNOWN: String = "unresolved_not_unknown"
  const val VIOLATION_EFFECTS_WITHOUT_ACTION: String = "effects_without_action"
  const val VIOLATION_LENGTH_EXCEEDED: String = "length_exceeded"
  const val VIOLATION_COUNT_EXCEEDED: String = "count_exceeded"
  const val VIOLATION_BLANK: String = "blank"
  const val VIOLATION_INVALID_DIGEST: String = "invalid_digest"

  const val AGENT_SUSPECTED_CAUSE: String = "suspected_cause"
  const val AGENT_EVIDENCE_REFS: String = "evidence_refs"

  const val DEGRADATION_SEPARATOR: String = ","
}
