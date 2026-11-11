package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalCardOption
import dev.skillbill.desktop.core.domain.model.ApprovalCardScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The saved form of an approval card in a chat file. The complete record is stored, never clipped, so history shows the
 * request and its outcome exactly. The runtime's grant answer is not a record field and is never saved: a card read back
 * has no grants, so history cannot enable a control.
 */
private object ApprovalKeys {
  const val KEY = "key"
  const val APPROVAL_ID = "approval_id"
  const val REPOSITORY_IDENTITY = "repository_identity"
  const val RUN_ID = "run_id"
  const val PROCESS_GENERATION = "process_generation"
  const val PROVIDER = "provider"
  const val PROVIDER_REQUEST_ID = "provider_request_id"
  const val PROVIDER_REQUEST_ID_IS_INTEGER = "provider_request_id_is_integer"
  const val CHECKOUT_ROOT = "checkout_root"
  const val PROVIDER_SESSION_ID = "provider_session_id"
  const val TITLE = "title"
  const val REASON = "reason"
  const val SUBJECT = "subject"
  const val INPUT_TEXT = "input_text"
  const val OPTIONS = "options"
  const val OPTION_ID = "option_id"
  const val LABEL = "label"
  const val KIND = "kind"
  const val ACTION_FINGERPRINT = "action_fingerprint"
  const val SCOPE = "authority_scope"
  const val PROFILE_IDENTITY = "profile_identity"
  const val PROTOCOL_REVISION = "protocol_revision"
  const val ACTION_KIND = "action_kind"
  const val WORKING_DIRECTORY = "working_directory"
  const val CREATED_AT = "created_at"
  const val DEADLINE_AT = "deadline_at"
  const val STATE = "state"
  const val DECISION_SOURCE = "decision_source"
  const val SELECTED_OPTION_ID = "selected_option_id"
  const val RULE_ID = "rule_id"
  const val RULE_PATTERN = "rule_pattern"
  const val PROVIDER_PAYLOAD = "provider_payload"
  const val DELIVERY = "delivery"
  const val PROVIDER_DISPOSITION = "provider_disposition"
  const val REPOSITORY_NAME = "repository_name"
}

internal fun ApprovalCard.toJson(): JsonObject = buildJsonObject {
  put(
    ApprovalKeys.KEY,
    buildJsonObject {
      put(ApprovalKeys.APPROVAL_ID, key.approvalId)
      put(ApprovalKeys.REPOSITORY_IDENTITY, key.repositoryIdentity)
      put(ApprovalKeys.RUN_ID, key.runId)
      put(ApprovalKeys.PROCESS_GENERATION, key.processGeneration)
      put(ApprovalKeys.PROVIDER, key.provider)
      put(ApprovalKeys.PROVIDER_REQUEST_ID, key.providerRequestId)
      put(ApprovalKeys.PROVIDER_REQUEST_ID_IS_INTEGER, key.providerRequestIdIsInteger)
    },
  )
  put(ApprovalKeys.CHECKOUT_ROOT, checkoutRoot)
  providerSessionId?.let { put(ApprovalKeys.PROVIDER_SESSION_ID, it) }
  put(ApprovalKeys.TITLE, title)
  reason?.let { put(ApprovalKeys.REASON, it) }
  put(ApprovalKeys.SUBJECT, subject)
  put(ApprovalKeys.INPUT_TEXT, inputText)
  put(
    ApprovalKeys.OPTIONS,
    buildJsonArray {
      options.forEach { option ->
        add(
          buildJsonObject {
            put(ApprovalKeys.OPTION_ID, option.optionId)
            put(ApprovalKeys.LABEL, option.label)
            put(ApprovalKeys.KIND, option.kind)
          },
        )
      }
    },
  )
  actionFingerprint?.let { put(ApprovalKeys.ACTION_FINGERPRINT, it) }
  authorityScope?.let { scope ->
    put(
      ApprovalKeys.SCOPE,
      buildJsonObject {
        put(ApprovalKeys.PROFILE_IDENTITY, scope.profileIdentity)
        put(ApprovalKeys.PROTOCOL_REVISION, scope.protocolRevision)
        put(ApprovalKeys.ACTION_KIND, scope.actionKind)
        put(ApprovalKeys.WORKING_DIRECTORY, scope.workingDirectory)
        put(ApprovalKeys.ACTION_FINGERPRINT, scope.actionFingerprint)
      },
    )
  }
  put(ApprovalKeys.CREATED_AT, createdAt)
  put(ApprovalKeys.DEADLINE_AT, deadlineAt)
  put(ApprovalKeys.STATE, state)
  decisionSource?.let { put(ApprovalKeys.DECISION_SOURCE, it) }
  selectedOptionId?.let { put(ApprovalKeys.SELECTED_OPTION_ID, it) }
  ruleId?.let { put(ApprovalKeys.RULE_ID, it) }
  rulePattern?.let { put(ApprovalKeys.RULE_PATTERN, it) }
  put(ApprovalKeys.PROVIDER_PAYLOAD, providerPayload)
  delivery?.let { put(ApprovalKeys.DELIVERY, it) }
  put(ApprovalKeys.PROVIDER_DISPOSITION, providerDisposition)
  put(ApprovalKeys.REPOSITORY_NAME, repositoryName)
}

/** Reads a saved card back; a record missing a required field is skipped and counted, never half shown. */
internal fun JsonObject.toApprovalCard(diagnostics: Diagnostics): ApprovalCard? {
  val card = decodeApprovalCard()
  if (card == null) diagnostics.record("chat.store_malformed_approval")
  return card
}

private fun JsonObject.decodeApprovalCard(): ApprovalCard? {
  val key = (get(ApprovalKeys.KEY) as? JsonObject)?.toKey() ?: return null
  return ApprovalCard(
    key = key,
    checkoutRoot = string(ApprovalKeys.CHECKOUT_ROOT) ?: return null,
    providerSessionId = string(ApprovalKeys.PROVIDER_SESSION_ID),
    title = string(ApprovalKeys.TITLE) ?: return null,
    reason = string(ApprovalKeys.REASON),
    subject = string(ApprovalKeys.SUBJECT) ?: return null,
    inputText = string(ApprovalKeys.INPUT_TEXT) ?: return null,
    options = (get(ApprovalKeys.OPTIONS) as? JsonArray)?.map { (it as? JsonObject)?.toOption() ?: return null } ?: return null,
    actionFingerprint = string(ApprovalKeys.ACTION_FINGERPRINT),
    authorityScope = (get(ApprovalKeys.SCOPE) as? JsonObject)?.let { it.toScope() ?: return null },
    createdAt = string(ApprovalKeys.CREATED_AT) ?: return null,
    deadlineAt = string(ApprovalKeys.DEADLINE_AT) ?: return null,
    state = string(ApprovalKeys.STATE) ?: return null,
    decisionSource = string(ApprovalKeys.DECISION_SOURCE),
    selectedOptionId = string(ApprovalKeys.SELECTED_OPTION_ID),
    ruleId = string(ApprovalKeys.RULE_ID),
    rulePattern = string(ApprovalKeys.RULE_PATTERN),
    providerPayload = string(ApprovalKeys.PROVIDER_PAYLOAD) ?: return null,
    delivery = string(ApprovalKeys.DELIVERY),
    providerDisposition = string(ApprovalKeys.PROVIDER_DISPOSITION) ?: "none",
    // The runtime's grant answer is never read from a file.
    grants = null,
    repositoryName = string(ApprovalKeys.REPOSITORY_NAME).orEmpty(),
  )
}

private fun JsonObject.toKey(): ApprovalCardKey? = ApprovalCardKey(
  approvalId = string(ApprovalKeys.APPROVAL_ID) ?: return null,
  repositoryIdentity = string(ApprovalKeys.REPOSITORY_IDENTITY) ?: return null,
  runId = string(ApprovalKeys.RUN_ID) ?: return null,
  processGeneration = string(ApprovalKeys.PROCESS_GENERATION) ?: return null,
  provider = string(ApprovalKeys.PROVIDER) ?: return null,
  providerRequestId = string(ApprovalKeys.PROVIDER_REQUEST_ID) ?: return null,
  providerRequestIdIsInteger = (get(ApprovalKeys.PROVIDER_REQUEST_ID_IS_INTEGER) as? JsonPrimitive)?.booleanOrNull ?: return null,
)

private fun JsonObject.toOption(): ApprovalCardOption? = ApprovalCardOption(
  optionId = string(ApprovalKeys.OPTION_ID) ?: return null,
  label = string(ApprovalKeys.LABEL) ?: return null,
  kind = string(ApprovalKeys.KIND) ?: return null,
)

private fun JsonObject.toScope(): ApprovalCardScope? = ApprovalCardScope(
  profileIdentity = string(ApprovalKeys.PROFILE_IDENTITY) ?: return null,
  protocolRevision = string(ApprovalKeys.PROTOCOL_REVISION) ?: return null,
  actionKind = string(ApprovalKeys.ACTION_KIND) ?: return null,
  workingDirectory = string(ApprovalKeys.WORKING_DIRECTORY) ?: return null,
  actionFingerprint = string(ApprovalKeys.ACTION_FINGERPRINT) ?: return null,
)
