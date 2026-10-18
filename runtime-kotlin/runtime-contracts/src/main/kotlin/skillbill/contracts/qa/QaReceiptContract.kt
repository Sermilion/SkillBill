package skillbill.contracts.qa

import skillbill.contracts.SharedPayloadKeys

const val QA_RECEIPT_CONTRACT_VERSION: String = "0.1"
const val QA_DEVICE_LEASE_CONTRACT_VERSION: String = "0.1"

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

object QaReceiptPayloadKeys {
  const val CONTRACT_VERSION = SharedPayloadKeys.CONTRACT_VERSION
  const val VERDICT = SharedPayloadKeys.VERDICT
  const val IDENTITY = "identity"
  const val HEAD_REVISION = "head_revision"
  const val TREE_IDENTITY = "tree_identity"
  const val ARTIFACT = "artifact"
  const val KIND = "kind"
  const val PATH_REF = "path_ref"
  const val SHA256 = "sha256"
  const val POLICY = "policy"
  const val TARGET = "target"
  const val PLATFORM = "platform"
  const val DEVICE_FINGERPRINT = "device_fingerprint"
  const val SELECTION = "selection"
  const val TOOL_VERSIONS = "tool_versions"
  const val FLOWS = "flows"
  const val ID = "id"
  const val NAME = "name"
  const val STATUS = SharedPayloadKeys.STATUS
  const val HARDWARE_ONLY = "hardware_only"
  const val UNTESTED_REASON = "untested_reason"
  const val ASSERTIONS = "assertions"
  const val FLOW_ID = "flow_id"
  const val DESCRIPTION = "description"
  const val OUTCOME = "outcome"
  const val CRITERIA = "criteria"
  const val FINDINGS = "findings"
  const val AFFECTED_CRITERIA = "affected_criteria"
  const val STEPS = "steps"
  const val EXPECTED = "expected"
  const val OBSERVED = "observed"
  const val EVIDENCE_REFS = "evidence_refs"
  const val PATH = "path"
  const val SKIP_REASON = "skip_reason"
  const val INCOMPLETE_REASON = "incomplete_reason"
  const val REASON_DETAIL = "reason_detail"
  const val FALLBACKS = "fallbacks"
  const val REASON = "reason"
  const val CLEANUP = "cleanup"
  const val RETAINED_RESOURCES = "retained_resources"
  const val GUIDANCE = "guidance"
}
