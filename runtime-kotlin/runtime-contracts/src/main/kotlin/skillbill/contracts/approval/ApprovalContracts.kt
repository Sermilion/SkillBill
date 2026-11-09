package skillbill.contracts.approval

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import skillbill.error.approval.ApprovalFailureCode
import skillbill.error.core.SkillBillRuntimeException

const val OPERATOR_APPROVAL_CONTRACT_VERSION = "1.0"

object ApprovalPayloadKeys {
  const val CONTRACT_VERSION = "contract_version"
  const val APPROVAL_ID = "approval_id"
  const val REPOSITORY_IDENTITY = "repository_identity"
  const val CHECKOUT_ROOT = "checkout_root"
  const val RUN_ID = "run_id"
  const val PROCESS_GENERATION = "process_generation"
  const val PROVIDER = "provider"
  const val PROVIDER_REQUEST_ID = "provider_request_id"
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
  const val AUTHORITY_SCOPE = "authority_scope"
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
  const val REVOKED_AT = "revoked_at"
  const val PROVIDER_PAYLOAD = "provider_payload"
}

object ApprovalContractJson {
  fun encode(request: ApprovalRequestRecord): String = requestObject(request).toString()

  fun decode(raw: String): ApprovalRequestRecord = requireSupportedVersion(parse(raw))

  private fun parse(raw: String): ApprovalRequestRecord =
    try {
      requestRecord(Json.parseToJsonElement(raw))
    } catch (failure: IllegalArgumentException) {
      throw SkillBillRuntimeException(
        ApprovalFailureCode.INVALID_APPROVAL_CONTRACT,
        "Stored approval record is corrupt.",
        failure,
      )
    }

  private fun requireSupportedVersion(request: ApprovalRequestRecord): ApprovalRequestRecord {
    if (request.contractVersion != OPERATOR_APPROVAL_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        ApprovalFailureCode.APPROVAL_VERSION_MISMATCH,
        "Unsupported operator approval contract version ${request.contractVersion}.",
      )
    }
    return request
  }
}

data class ApprovalRequestRecord(
  val contractVersion: String,
  val approvalId: String,
  val repositoryIdentity: String,
  val checkoutRoot: String,
  val runId: String,
  val processGeneration: String,
  val provider: String,
  val providerRequestId: ApprovalProviderRequestId,
  val providerSessionId: String? = null,
  val title: String,
  val reason: String? = null,
  val subject: String,
  val inputText: String,
  val options: List<ApprovalOption>,
  val actionFingerprint: String? = null,
  val authorityScope: ApprovalAuthorityScope? = null,
  val createdAt: String,
  val deadlineAt: String,
  val state: ApprovalState,
  val decisionSource: ApprovalDecisionSource? = null,
  val selectedOptionId: String? = null,
  val ruleId: String? = null,
  val providerPayload: String,
)

data class ApprovalOption(
  val optionId: String,
  val label: String,
  val kind: String,
)

sealed interface ApprovalProviderRequestId {
  data class Text(val value: String) : ApprovalProviderRequestId

  /** A JSON integer id kept verbatim; only the canonical JSON integer grammar is accepted so it re-encodes as-is. */
  data class Integer(val value: String) : ApprovalProviderRequestId {
    init {
      require(JSON_INTEGER.matches(value)) { "Approval request id integer is invalid" }
    }
  }
}

private val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")

data class ApprovalAuthorityScope(
  val profileIdentity: String,
  val protocolRevision: String,
  val actionKind: String,
  val workingDirectory: String,
  val actionFingerprint: String,
)

data class ApprovalRuleRecord(
  val ruleId: String,
  val repositoryIdentity: String,
  val provider: String,
  val profileIdentity: String,
  val protocolRevision: String,
  val actionKind: String,
  val workingDirectory: String,
  val actionFingerprint: String,
  val createdAt: String,
  val revokedAt: String? = null,
  val pattern: String? = null,
)

enum class ApprovalState(val wireValue: String) {
  PENDING("pending"),
  ALLOWED("allowed"),
  DENIED("denied"),
  CANCELLED("cancelled"),
  EXPIRED("expired"),
  UNSUPPORTED("unsupported"),
  PROCESS_ENDED("process_ended"),
}

enum class ApprovalDecisionSource(val wireValue: String) {
  OPERATOR_ONCE("operator_once"),
  OPERATOR_RULE("operator_rule"),
  STORED_RULE("stored_rule"),
}

private val REQUEST_KEYS =
  setOf(
    ApprovalPayloadKeys.CONTRACT_VERSION,
    ApprovalPayloadKeys.APPROVAL_ID,
    ApprovalPayloadKeys.REPOSITORY_IDENTITY,
    ApprovalPayloadKeys.CHECKOUT_ROOT,
    ApprovalPayloadKeys.RUN_ID,
    ApprovalPayloadKeys.PROCESS_GENERATION,
    ApprovalPayloadKeys.PROVIDER,
    ApprovalPayloadKeys.PROVIDER_REQUEST_ID,
    ApprovalPayloadKeys.PROVIDER_SESSION_ID,
    ApprovalPayloadKeys.TITLE,
    ApprovalPayloadKeys.REASON,
    ApprovalPayloadKeys.SUBJECT,
    ApprovalPayloadKeys.INPUT_TEXT,
    ApprovalPayloadKeys.OPTIONS,
    ApprovalPayloadKeys.ACTION_FINGERPRINT,
    ApprovalPayloadKeys.AUTHORITY_SCOPE,
    ApprovalPayloadKeys.CREATED_AT,
    ApprovalPayloadKeys.DEADLINE_AT,
    ApprovalPayloadKeys.STATE,
    ApprovalPayloadKeys.DECISION_SOURCE,
    ApprovalPayloadKeys.SELECTED_OPTION_ID,
    ApprovalPayloadKeys.RULE_ID,
    ApprovalPayloadKeys.PROVIDER_PAYLOAD,
  )

private val OPTION_KEYS = setOf(ApprovalPayloadKeys.OPTION_ID, ApprovalPayloadKeys.LABEL, ApprovalPayloadKeys.KIND)

private val SCOPE_KEYS =
  setOf(
    ApprovalPayloadKeys.PROFILE_IDENTITY,
    ApprovalPayloadKeys.PROTOCOL_REVISION,
    ApprovalPayloadKeys.ACTION_KIND,
    ApprovalPayloadKeys.WORKING_DIRECTORY,
    ApprovalPayloadKeys.ACTION_FINGERPRINT,
  )

private fun requestObject(request: ApprovalRequestRecord): JsonObject =
  JsonObject(
    linkedMapOf(
      ApprovalPayloadKeys.CONTRACT_VERSION to JsonPrimitive(request.contractVersion),
      ApprovalPayloadKeys.APPROVAL_ID to JsonPrimitive(request.approvalId),
      ApprovalPayloadKeys.REPOSITORY_IDENTITY to JsonPrimitive(request.repositoryIdentity),
      ApprovalPayloadKeys.CHECKOUT_ROOT to JsonPrimitive(request.checkoutRoot),
      ApprovalPayloadKeys.RUN_ID to JsonPrimitive(request.runId),
      ApprovalPayloadKeys.PROCESS_GENERATION to JsonPrimitive(request.processGeneration),
      ApprovalPayloadKeys.PROVIDER to JsonPrimitive(request.provider),
      ApprovalPayloadKeys.PROVIDER_REQUEST_ID to requestIdElement(request.providerRequestId),
      ApprovalPayloadKeys.PROVIDER_SESSION_ID to JsonPrimitive(request.providerSessionId),
      ApprovalPayloadKeys.TITLE to JsonPrimitive(request.title),
      ApprovalPayloadKeys.REASON to JsonPrimitive(request.reason),
      ApprovalPayloadKeys.SUBJECT to JsonPrimitive(request.subject),
      ApprovalPayloadKeys.INPUT_TEXT to JsonPrimitive(request.inputText),
      ApprovalPayloadKeys.OPTIONS to JsonArray(request.options.map(::optionObject)),
      ApprovalPayloadKeys.ACTION_FINGERPRINT to JsonPrimitive(request.actionFingerprint),
      ApprovalPayloadKeys.AUTHORITY_SCOPE to (request.authorityScope?.let(::scopeObject) ?: JsonNull),
      ApprovalPayloadKeys.CREATED_AT to JsonPrimitive(request.createdAt),
      ApprovalPayloadKeys.DEADLINE_AT to JsonPrimitive(request.deadlineAt),
      ApprovalPayloadKeys.STATE to JsonPrimitive(request.state.wireValue),
      ApprovalPayloadKeys.DECISION_SOURCE to JsonPrimitive(request.decisionSource?.wireValue),
      ApprovalPayloadKeys.SELECTED_OPTION_ID to JsonPrimitive(request.selectedOptionId),
      ApprovalPayloadKeys.RULE_ID to JsonPrimitive(request.ruleId),
      ApprovalPayloadKeys.PROVIDER_PAYLOAD to JsonPrimitive(request.providerPayload),
    ),
  )

private fun optionObject(option: ApprovalOption): JsonObject =
  JsonObject(
    linkedMapOf(
      ApprovalPayloadKeys.OPTION_ID to JsonPrimitive(option.optionId),
      ApprovalPayloadKeys.LABEL to JsonPrimitive(option.label),
      ApprovalPayloadKeys.KIND to JsonPrimitive(option.kind),
    ),
  )

private fun scopeObject(scope: ApprovalAuthorityScope): JsonObject =
  JsonObject(
    linkedMapOf(
      ApprovalPayloadKeys.PROFILE_IDENTITY to JsonPrimitive(scope.profileIdentity),
      ApprovalPayloadKeys.PROTOCOL_REVISION to JsonPrimitive(scope.protocolRevision),
      ApprovalPayloadKeys.ACTION_KIND to JsonPrimitive(scope.actionKind),
      ApprovalPayloadKeys.WORKING_DIRECTORY to JsonPrimitive(scope.workingDirectory),
      ApprovalPayloadKeys.ACTION_FINGERPRINT to JsonPrimitive(scope.actionFingerprint),
    ),
  )

@OptIn(ExperimentalSerializationApi::class)
private fun requestIdElement(id: ApprovalProviderRequestId): JsonElement =
  when (id) {
    is ApprovalProviderRequestId.Text -> JsonPrimitive(id.value)
    is ApprovalProviderRequestId.Integer -> JsonUnquotedLiteral(id.value)
  }

private fun requestRecord(element: JsonElement): ApprovalRequestRecord {
  val fields = strictObject(element, REQUEST_KEYS, "approval record")
  return ApprovalRequestRecord(
    contractVersion = fields.requiredText(ApprovalPayloadKeys.CONTRACT_VERSION),
    approvalId = fields.requiredText(ApprovalPayloadKeys.APPROVAL_ID),
    repositoryIdentity = fields.requiredText(ApprovalPayloadKeys.REPOSITORY_IDENTITY),
    checkoutRoot = fields.requiredText(ApprovalPayloadKeys.CHECKOUT_ROOT),
    runId = fields.requiredText(ApprovalPayloadKeys.RUN_ID),
    processGeneration = fields.requiredText(ApprovalPayloadKeys.PROCESS_GENERATION),
    provider = fields.requiredText(ApprovalPayloadKeys.PROVIDER),
    providerRequestId = requestId(fields[ApprovalPayloadKeys.PROVIDER_REQUEST_ID]),
    providerSessionId = fields.optionalText(ApprovalPayloadKeys.PROVIDER_SESSION_ID),
    title = fields.requiredText(ApprovalPayloadKeys.TITLE),
    reason = fields.optionalText(ApprovalPayloadKeys.REASON),
    subject = fields.requiredText(ApprovalPayloadKeys.SUBJECT),
    inputText = fields.requiredText(ApprovalPayloadKeys.INPUT_TEXT),
    options = options(fields[ApprovalPayloadKeys.OPTIONS]),
    actionFingerprint = fields.optionalText(ApprovalPayloadKeys.ACTION_FINGERPRINT),
    authorityScope = fields[ApprovalPayloadKeys.AUTHORITY_SCOPE]?.takeUnless { it is JsonNull }?.let(::scope),
    createdAt = fields.requiredText(ApprovalPayloadKeys.CREATED_AT),
    deadlineAt = fields.requiredText(ApprovalPayloadKeys.DEADLINE_AT),
    state = wireToken(fields.requiredText(ApprovalPayloadKeys.STATE), ApprovalState.entries, ApprovalState::wireValue),
    decisionSource =
      fields.optionalText(ApprovalPayloadKeys.DECISION_SOURCE)?.let { token ->
        wireToken(token, ApprovalDecisionSource.entries, ApprovalDecisionSource::wireValue)
      },
    selectedOptionId = fields.optionalText(ApprovalPayloadKeys.SELECTED_OPTION_ID),
    ruleId = fields.optionalText(ApprovalPayloadKeys.RULE_ID),
    providerPayload = fields.requiredText(ApprovalPayloadKeys.PROVIDER_PAYLOAD),
  )
}

private fun options(element: JsonElement?): List<ApprovalOption> {
  val array = element as? JsonArray ?: throw SerializationException("Approval options must be an array")
  return array.map { option ->
    val fields = strictObject(option, OPTION_KEYS, "approval option")
    ApprovalOption(
      optionId = fields.requiredText(ApprovalPayloadKeys.OPTION_ID),
      label = fields.requiredText(ApprovalPayloadKeys.LABEL),
      kind = fields.requiredText(ApprovalPayloadKeys.KIND),
    )
  }
}

private fun scope(element: JsonElement): ApprovalAuthorityScope {
  val fields = strictObject(element, SCOPE_KEYS, "approval authority scope")
  return ApprovalAuthorityScope(
    profileIdentity = fields.requiredText(ApprovalPayloadKeys.PROFILE_IDENTITY),
    protocolRevision = fields.requiredText(ApprovalPayloadKeys.PROTOCOL_REVISION),
    actionKind = fields.requiredText(ApprovalPayloadKeys.ACTION_KIND),
    workingDirectory = fields.requiredText(ApprovalPayloadKeys.WORKING_DIRECTORY),
    actionFingerprint = fields.requiredText(ApprovalPayloadKeys.ACTION_FINGERPRINT),
  )
}

private fun requestId(element: JsonElement?): ApprovalProviderRequestId {
  val primitive = (element as? JsonPrimitive)?.takeUnless { it is JsonNull }
  if (primitive == null) throw SerializationException("Approval request id must be a string or integer")
  if (primitive.isString) return ApprovalProviderRequestId.Text(primitive.content)
  if (!JSON_INTEGER.matches(primitive.content)) {
    throw SerializationException("Approval request id integer is invalid")
  }
  return ApprovalProviderRequestId.Integer(primitive.content)
}

private fun strictObject(
  element: JsonElement,
  allowedKeys: Set<String>,
  what: String,
): JsonObject {
  val fields = element as? JsonObject ?: throw SerializationException("The $what must be a JSON object")
  val unknown = fields.keys - allowedKeys
  if (unknown.isNotEmpty()) throw SerializationException("The $what has unknown keys $unknown")
  return fields
}

private fun JsonObject.requiredText(key: String): String =
  optionalText(key) ?: throw SerializationException("Approval field $key is required")

private fun JsonObject.optionalText(key: String): String? {
  val value = this[key] ?: return null
  if (value is JsonNull) return null
  val primitive = value as? JsonPrimitive
  if (primitive == null || !primitive.isString) throw SerializationException("Approval field $key must be a string")
  return primitive.content
}

private fun <T> wireToken(
  token: String,
  values: List<T>,
  wireValue: (T) -> String,
): T = values.firstOrNull { value -> wireValue(value) == token } ?: throw SerializationException("Unknown token $token")
