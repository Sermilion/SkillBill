package skillbill.contracts.workflow.identity.evidence

import skillbill.contracts.SharedPayloadKeys

object ValidationEvidencePayloadKeys {
  const val BUILD_RECEIPT: String = "build_receipt"
  const val VALIDATION_PASSED: String = "validation_passed"
  const val VALIDATION_EVIDENCE: String = "validation_evidence"
  const val VALIDATION_RESULT: String = "validation_result"
  const val CONTRACT_VERSION: String = SharedPayloadKeys.CONTRACT_VERSION
  const val RESULTS: String = "results"
  const val COMMAND: String = "command"
  const val EXIT_CODE: String = "exit_code"
  const val INTEGRITY_PROBLEM: String = "integrity_problem"
  const val COMPLETED_SUBTASK_VALIDATION: String = "completed_subtask_validation"
  const val VALIDATION_STATUS: String = "validation_status"
  const val CHECKS: String = "checks"
  const val GATE_RUN_COUNT: String = "gate_run_count"
  const val GATE_RUNS: String = "gate_runs"
  const val EXECUTED_CHECKS: String = "executed_checks"
  const val EXECUTED_WORK_UNITS: String = "executed_work_units"
  const val DURATION_MS: String = "duration_ms"
  const val OUTCOME: String = "outcome"
  const val CACHE_MODE: String = "cache_mode"
  const val LAST_AGENT_UNFIXED_CRITERIA: String = "last_agent_unfixed_criteria"
}
