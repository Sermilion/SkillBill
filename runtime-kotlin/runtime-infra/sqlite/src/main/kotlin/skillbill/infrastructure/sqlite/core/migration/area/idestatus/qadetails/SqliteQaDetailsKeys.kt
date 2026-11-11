package skillbill.infrastructure.sqlite.core.migration.area.idestatus.qadetails

import skillbill.contracts.qa.QaChecklistPayloadKeys
import skillbill.contracts.qa.QaDeviceLeasePayloadKeys
import skillbill.contracts.qa.QaOutcomePayloadKeys
import skillbill.contracts.qa.QaOwnedResourcePayloadKeys

internal object SqliteQaDetailsKeys {
  const val PREVIEW: String = "preview"
  const val INVOCATION_ID: String = QaDeviceLeasePayloadKeys.INVOCATION_ID
  const val TARGETS: String = QaChecklistPayloadKeys.TARGETS
  const val ACTIVE: String = "active"
  const val REPORT_PATH: String = QaChecklistPayloadKeys.REPORT_PATH
  const val INVOCATION_DIRECTORY: String = QaChecklistPayloadKeys.INVOCATION_DIRECTORY
  const val SKIP_REASON: String = QaOutcomePayloadKeys.SKIP_REASON
  const val ASSERTIONS: String = "assertions"
  const val OUTCOME: String = QaOutcomePayloadKeys.OUTCOME
  const val TITLE: String = QaChecklistPayloadKeys.TITLE
  const val DETAIL: String = QaChecklistPayloadKeys.DETAIL
  const val EVIDENCE_PATHS: String = "evidence_paths"
  const val COVERAGE: String = "coverage"
  const val KIND: String = QaOwnedResourcePayloadKeys.KIND
  const val VERDICT: String = QaOutcomePayloadKeys.VERDICT
  const val REASON: String = QaOutcomePayloadKeys.REASON
  const val BACKEND: String = "backend"
  const val RETAINED_RESOURCES: String = "retained_resources"
  const val IDENTITY: String = QaOwnedResourcePayloadKeys.IDENTITY
  const val TEMPORARY_EDITS: String = "temporary_edits"
  const val PATH: String = QaOutcomePayloadKeys.PATH
  const val NOT_RESTORED: String = "not_restored"
  const val FINDINGS: String = QaOutcomePayloadKeys.FINDINGS
  const val ID: String = QaOutcomePayloadKeys.ID
  const val OBSERVED: String = QaOutcomePayloadKeys.OBSERVED
  const val EXPECTED: String = QaOutcomePayloadKeys.EXPECTED
  const val STEPS: String = QaOutcomePayloadKeys.STEPS
  const val HISTORY_AVAILABILITY: String = "history_availability"
  const val HISTORY_CONTRACT_VERSION: String = "history_contract_version"
  const val OCCURRENCES: String = "occurrences"
  const val OCCURRENCE_ID: String = "occurrence_id"
  const val PHASE_ATTEMPT: String = "phase_attempt"
  const val WORKSPACE_INVOCATION_ID: String = "workspace_invocation_id"
  const val STARTED_AT: String = "started_at"
  const val FINISHED_AT: String = "finished_at"
  const val DISPOSITION: String = "disposition"
  const val TESTED_TREE_IDENTITY: String = "tested_tree_identity"
  const val RESULT_REFERENCE: String = "result_reference"
  const val FRAME_REFERENCE: String = "frame_reference"
  const val FRAME_AVAILABILITY: String = "frame_availability"
  const val SCREENSHOTS: String = "screenshots"
  const val STAGES: String = "stages"
  const val LABEL: String = "label"
  const val FORMAT: String = "format"
  const val NAME: String = "name"
  const val STATE: String = "state"
}
