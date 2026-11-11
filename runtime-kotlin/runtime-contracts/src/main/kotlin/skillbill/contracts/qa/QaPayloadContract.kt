package skillbill.contracts.qa

import skillbill.contracts.SharedPayloadKeys

const val QA_REPAIR_STATE_CONTRACT_VERSION: String = "0.2"
const val QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION: String = "0.1"
const val QA_DEVICE_LEASE_CONTRACT_VERSION: String = "0.1"
const val QA_OWNED_RESOURCE_CONTRACT_VERSION: String = "0.1"
const val QA_CHECKLIST_CONTRACT_VERSION: String = "0.1"
const val QA_PANEL_HISTORY_CONTRACT_VERSION: String = "0.1"
const val QA_CHECKLIST_MAX_ENCODED_BYTES: Int = 512 * 1024

object QaDeviceLeasePayloadKeys {
  const val CONTRACT_VERSION = SharedPayloadKeys.CONTRACT_VERSION
  const val DEVICE_FINGERPRINT = "device_fingerprint"
  const val INVOCATION_ID = "invocation_id"
  const val OWNER_TOKEN = "owner_token"
  const val PROCESS = "process"
  const val HOST_IDENTITY = "host_identity"
  const val BOOT_IDENTITY = "boot_identity"
  const val PID = "pid"
  const val PROCESS_BIRTH_TOKEN = "process_birth_token"
  const val ACQUIRED_AT = "acquired_at"
  const val REASON = "reason"

  val OWNER_FIELDS: Set<String> = setOf(CONTRACT_VERSION, INVOCATION_ID, OWNER_TOKEN, PROCESS, ACQUIRED_AT)
  val LEASE_FIELDS: Set<String> = OWNER_FIELDS + DEVICE_FINGERPRINT
  val PROCESS_FIELDS: Set<String> = setOf(HOST_IDENTITY, BOOT_IDENTITY, PID, PROCESS_BIRTH_TOKEN)
}

object QaOwnedResourcePayloadKeys {
  const val CONTRACT_VERSION = SharedPayloadKeys.CONTRACT_VERSION
  const val STATUS = SharedPayloadKeys.STATUS
  const val KIND = "kind"
  const val IDENTITY = "identity"
  const val REGISTERED_AT = "registered_at"
  const val PROCESS_STARTED_AT = "process_started_at"
  const val OWNER_INVOCATION_ID = "owner_invocation_id"

  val RECORD_FIELDS: Set<String> =
    setOf(CONTRACT_VERSION, QaDeviceLeasePayloadKeys.INVOCATION_ID, KIND, IDENTITY, REGISTERED_AT)
  val PROCESS_RECORD_FIELDS: Set<String> = RECORD_FIELDS + PROCESS_STARTED_AT
}

object QaChecklistPayloadKeys {
  const val CONTRACT_VERSION = SharedPayloadKeys.CONTRACT_VERSION
  const val INVOCATION_DIRECTORY = "invocation_directory"
  const val REPORT_PATH = "report_path"
  const val TESTED_COMMIT = "tested_commit"
  const val CRITERIA_SOURCE = "criteria_source"
  const val TARGETS = "targets"
  const val NAME = "name"
  const val STATE = "state"
  const val DETAIL = "detail"
  const val UPDATED_AT = "updated_at"
  const val PLAN_REGISTERED_AT = "plan_registered_at"
  const val CASES = "cases"
  const val ID = "id"
  const val TITLE = "title"
  const val STEPS = "steps"
  const val TEXT = "text"
  const val STATUS = SharedPayloadKeys.STATUS
  const val NOTE = "note"
  const val FINDING_ID = "finding_id"
  const val EVIDENCE = "evidence"
  const val FINDINGS = "findings"
  const val RECORDED_AT = "recorded_at"
  const val LAST_STEP_UPDATE_AT = "last_step_update_at"
  const val REASON = "reason"
  const val PATH = "path"
  const val REJECTED_EVIDENCE = "rejected_evidence"

  val CHECKLIST_FIELDS: Set<String> =
    setOf(
      CONTRACT_VERSION, INVOCATION_DIRECTORY, REPORT_PATH, TESTED_COMMIT, CRITERIA_SOURCE, TARGETS,
      PLAN_REGISTERED_AT, CASES, FINDINGS, LAST_STEP_UPDATE_AT,
    )
  val TARGET_FIELDS: Set<String> = setOf(NAME, STATE, DETAIL, UPDATED_AT)
  val CASE_FIELDS: Set<String> = setOf(ID, TITLE, STEPS)
  val STEP_FIELDS: Set<String> = setOf(TEXT, STATUS, NOTE, FINDING_ID, EVIDENCE, UPDATED_AT)
  val FINDING_FIELDS: Set<String> = setOf(ID, TITLE, RECORDED_AT)
  val PLAN_FIELDS: Set<String> = setOf(CONTRACT_VERSION, CASES)
  val PLAN_STEP_FIELDS: Set<String> = setOf(TEXT)
}

object QaOutcomePayloadKeys {
  const val VERDICT = SharedPayloadKeys.VERDICT
  const val STATUS = SharedPayloadKeys.STATUS
  const val ID = "id"
  const val FINDINGS = "findings"
  const val OBSERVED = "observed"
  const val STEPS = "steps"
  const val EXPECTED = "expected"
  const val EVIDENCE_REFS = "evidence_refs"
  const val PATH = "path"
  const val SKIP_REASON = "skip_reason"
  const val INCOMPLETE_REASON = "incomplete_reason"
  const val REASON_DETAIL = "reason_detail"
  const val OUTCOME = "outcome"
  const val REASON = "reason"
  const val REPORT_TEXT = "report_text"
  const val CLEANUP = "cleanup"
}

object QaRepairStatePayloadKeys {
  const val CONTRACT_VERSION = SharedPayloadKeys.CONTRACT_VERSION
  const val SUBTASK_ID = "subtask_id"
  const val QA_FIX_STEP_ID = "qa_fix_step_id"
  const val MAX_ATTEMPTS = "max_attempts"
  const val CONSUMED_ATTEMPTS = "consumed_attempts"
  const val ATTEMPTS = "attempts"
  const val ATTEMPT_NUMBER = "attempt_number"
  const val RECEIPT_TREE_IDENTITY = "receipt_tree_identity"
  const val CONSUMED_FINDING_IDS = "consumed_finding_ids"
  const val REPAIR_OUTPUT_REF = "repair_output_ref"
  const val OUTCOME = "outcome"
  const val OPEN_FINDINGS = "open_findings"
  const val ID = "id"
  const val AFFECTED_CRITERIA = "affected_criteria"
  const val RECEIPT_REF = "receipt_ref"
  const val RECEIPT_REFS = "receipt_refs"
  const val LAST_RECEIPT_TREE_IDENTITY = "last_receipt_tree_identity"
}
