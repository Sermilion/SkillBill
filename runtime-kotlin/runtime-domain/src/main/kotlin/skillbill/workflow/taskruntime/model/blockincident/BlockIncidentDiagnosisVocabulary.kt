package skillbill.workflow.taskruntime.model.blockincident

import skillbill.contracts.workflow.identity.blockincident.BlockIncidentDiagnosisKeys

enum class BlockIncidentCertainty(val wireValue: String) {
  DIAGNOSED(BlockIncidentDiagnosisKeys.CERTAINTY_DIAGNOSED),
  SUSPECTED(BlockIncidentDiagnosisKeys.CERTAINTY_SUSPECTED),
  UNKNOWN(BlockIncidentDiagnosisKeys.CERTAINTY_UNKNOWN),
}

enum class BlockIncidentResponsibleCategory(val wireValue: String) {
  PROJECT_SOURCE(BlockIncidentDiagnosisKeys.CATEGORY_PROJECT_SOURCE),
  USER_ENVIRONMENT(BlockIncidentDiagnosisKeys.CATEGORY_USER_ENVIRONMENT),
  EXTERNAL_SERVICE(BlockIncidentDiagnosisKeys.CATEGORY_EXTERNAL_SERVICE),
  SKILL_BILL_DEFECT(BlockIncidentDiagnosisKeys.CATEGORY_SKILL_BILL_DEFECT),
  UNRESOLVED(BlockIncidentDiagnosisKeys.CATEGORY_UNRESOLVED),
}

enum class BlockIncidentPrerequisiteKind(val wireValue: String) {
  CREDENTIALS_CONFIGURED(BlockIncidentDiagnosisKeys.PREREQUISITE_CREDENTIALS_CONFIGURED),
  CONFIGURATION_FIXED(BlockIncidentDiagnosisKeys.PREREQUISITE_CONFIGURATION_FIXED),
  PROJECT_EDIT_COMPLETED(BlockIncidentDiagnosisKeys.PREREQUISITE_PROJECT_EDIT_COMPLETED),
  EXTERNAL_SERVICE_AVAILABLE(BlockIncidentDiagnosisKeys.PREREQUISITE_EXTERNAL_SERVICE_AVAILABLE),
}

enum class BlockIncidentPrerequisiteCheck(val wireValue: String) {
  RUNTIME_CHECKABLE(BlockIncidentDiagnosisKeys.CHECK_RUNTIME_CHECKABLE),
  USER_CONFIRMED(BlockIncidentDiagnosisKeys.CHECK_USER_CONFIRMED),
}

enum class BlockIncidentRecoveryAction(val wireValue: String) {
  REOPEN_BLOCKED_PHASE(BlockIncidentDiagnosisKeys.ACTION_REOPEN_BLOCKED_PHASE),
  RETRY_AFTER_PREREQUISITE(BlockIncidentDiagnosisKeys.ACTION_RETRY_AFTER_PREREQUISITE),
  NONE(BlockIncidentDiagnosisKeys.ACTION_NONE),
}

enum class BlockIncidentDegradation(val wireValue: String) {
  READ_ONLY_NOT_ENFORCED(BlockIncidentDiagnosisKeys.DEGRADATION_READ_ONLY_NOT_ENFORCED),
  AGENT_UNAVAILABLE(BlockIncidentDiagnosisKeys.DEGRADATION_AGENT_UNAVAILABLE),
  AGENT_TIMED_OUT(BlockIncidentDiagnosisKeys.DEGRADATION_AGENT_TIMED_OUT),
  AGENT_OUTPUT_REJECTED(BlockIncidentDiagnosisKeys.DEGRADATION_AGENT_OUTPUT_REJECTED),
  EVIDENCE_UNAVAILABLE(BlockIncidentDiagnosisKeys.DEGRADATION_EVIDENCE_UNAVAILABLE),
  EVIDENCE_TRUNCATED(BlockIncidentDiagnosisKeys.DEGRADATION_EVIDENCE_TRUNCATED),
  DEADLINE_EXCEEDED(BlockIncidentDiagnosisKeys.DEGRADATION_DEADLINE_EXCEEDED),
  NOT_PICKED_UP(BlockIncidentDiagnosisKeys.DEGRADATION_NOT_PICKED_UP),
  CLAIM_TAKEN_OVER(BlockIncidentDiagnosisKeys.DEGRADATION_CLAIM_TAKEN_OVER),
  SPOOL_IMPORTED(BlockIncidentDiagnosisKeys.DEGRADATION_SPOOL_IMPORTED),
}

enum class BlockIncidentInvestigatorKind(val wireValue: String) {
  DETERMINISTIC(BlockIncidentDiagnosisKeys.INVESTIGATOR_DETERMINISTIC),
  DETERMINISTIC_WITH_AGENT(BlockIncidentDiagnosisKeys.INVESTIGATOR_DETERMINISTIC_WITH_AGENT),
}

enum class BlockIncidentDiagnosisEvidenceKind(val wireValue: String) {
  WORKFLOW_STATE(BlockIncidentDiagnosisKeys.EVIDENCE_WORKFLOW_STATE),
  PHASE_LEDGER(BlockIncidentDiagnosisKeys.EVIDENCE_PHASE_LEDGER),
  REJECTED_OUTPUT(BlockIncidentDiagnosisKeys.EVIDENCE_REJECTED_OUTPUT),
  PRODUCER_OUTPUT(BlockIncidentDiagnosisKeys.EVIDENCE_PRODUCER_OUTPUT),
  ATTEMPT_HISTORY(BlockIncidentDiagnosisKeys.EVIDENCE_ATTEMPT_HISTORY),
  RUNTIME_VERSION(BlockIncidentDiagnosisKeys.EVIDENCE_RUNTIME_VERSION),
  PHASE_BRIEFING(BlockIncidentDiagnosisKeys.EVIDENCE_PHASE_BRIEFING),
  ORIGINAL_FAILURE(BlockIncidentDiagnosisKeys.EVIDENCE_ORIGINAL_FAILURE),
}
