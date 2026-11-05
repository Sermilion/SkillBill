package skillbill.contracts.operationcard

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.operationcard.OperationCardFailureCode

object OperationCardPayloadKeys {
  const val CONTRACT_VERSION = "contract_version"
  const val LAUNCH_ID = "launch_id"
  const val OPERATION_ID = "operation_id"
  const val INVOCATION_ID = "invocation_id"
  const val REPOSITORY_IDENTITY = "repository_identity"
  const val DATABASE_IDENTITY = "database_identity"
  const val CHECKOUT_ROOT = "checkout_root"
  const val BRANCH = "branch"
  const val SOURCE_ANCHORS = "source_anchors"
  const val ORIGINAL_ARGUMENTS = "original_arguments"
  const val SELECTED_AGENT = "selected_agent"
  const val SELECTED_PROFILE = "selected_profile"
  const val TOKEN = "token"
  const val FULL_SUMMARY = "full_summary"
  const val PROPOSAL_DETAILS = "proposal_details"
  const val STATUS = "status"
  const val CONFIRMATION_INVOCATION_ID = "confirmation_invocation_id"
  const val SELECTION = "selection"
  const val EVIDENCE = "evidence"
  const val KIND = "kind"
  const val CRITERIA = "criteria"
  const val INTAKE = "intake"
  const val SPEC = "spec"
  const val TARGET = "target"
  const val MODE = "mode"
  const val QA = "qa"
  const val PULL_REQUEST = "pull_request"
  const val PUSH = "push"
  const val REPLIES = "replies"
  const val THREADS = "threads"
  const val ORDINAL = "ordinal"
  const val THREAD_ID = "thread_id"
  const val OPTIONS = "options"
  const val OPTION_ID = "option_id"
  const val LABEL = "label"
  const val RECOMMENDED = "recommended"
}

object OperationCardContractJson {
  fun encode(card: OperationCardProjection): String = cardObject(card).toString()

  fun encodeArguments(arguments: List<String>): String = JsonArray(arguments.map(::JsonPrimitive)).toString()

  fun decodeArguments(raw: String): List<String> =
    try {
      textList(Json.parseToJsonElement(raw), "launch arguments")
    } catch (failure: IllegalArgumentException) {
      throw SkillBillRuntimeException(
        OperationCardFailureCode.INVALID_OPERATION_CARD_CONTRACT,
        "Stored operation launch arguments are corrupt.",
        failure,
      )
    }

  fun decode(raw: String): OperationCardProjection {
    val element = parse(raw)
    val version =
      ((element as? JsonObject)?.get(OperationCardPayloadKeys.CONTRACT_VERSION) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf(String::isNotEmpty)
    if (version != null && version != OPERATOR_OPERATION_CARD_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        OperationCardFailureCode.OPERATION_CARD_VERSION_MISMATCH,
        "Unsupported operator operation card contract version $version.",
      )
    }
    return try {
      cardRecord(element)
    } catch (failure: IllegalArgumentException) {
      throw corrupt(failure)
    }
  }

  private fun parse(raw: String): JsonElement =
    try {
      Json.parseToJsonElement(raw)
    } catch (failure: IllegalArgumentException) {
      throw corrupt(failure)
    }

  private fun corrupt(failure: IllegalArgumentException): SkillBillRuntimeException =
    SkillBillRuntimeException(
      OperationCardFailureCode.INVALID_OPERATION_CARD_CONTRACT,
      "Stored operation card is corrupt.",
      failure,
    )
}

private val CARD_KEYS =
  setOf(
    OperationCardPayloadKeys.CONTRACT_VERSION,
    OperationCardPayloadKeys.LAUNCH_ID,
    OperationCardPayloadKeys.OPERATION_ID,
    OperationCardPayloadKeys.INVOCATION_ID,
    OperationCardPayloadKeys.REPOSITORY_IDENTITY,
    OperationCardPayloadKeys.DATABASE_IDENTITY,
    OperationCardPayloadKeys.CHECKOUT_ROOT,
    OperationCardPayloadKeys.BRANCH,
    OperationCardPayloadKeys.SOURCE_ANCHORS,
    OperationCardPayloadKeys.ORIGINAL_ARGUMENTS,
    OperationCardPayloadKeys.SELECTED_AGENT,
    OperationCardPayloadKeys.SELECTED_PROFILE,
    OperationCardPayloadKeys.TOKEN,
    OperationCardPayloadKeys.FULL_SUMMARY,
    OperationCardPayloadKeys.PROPOSAL_DETAILS,
    OperationCardPayloadKeys.STATUS,
    OperationCardPayloadKeys.CONFIRMATION_INVOCATION_ID,
    OperationCardPayloadKeys.SELECTION,
    OperationCardPayloadKeys.EVIDENCE,
  )

private val VERIFY_KEYS =
  setOf(
    OperationCardPayloadKeys.KIND,
    OperationCardPayloadKeys.CRITERIA,
    OperationCardPayloadKeys.INTAKE,
    OperationCardPayloadKeys.SPEC,
    OperationCardPayloadKeys.TARGET,
    OperationCardPayloadKeys.MODE,
    OperationCardPayloadKeys.QA,
  )

private val PR_REVIEW_FIX_KEYS =
  setOf(
    OperationCardPayloadKeys.KIND,
    OperationCardPayloadKeys.MODE,
    OperationCardPayloadKeys.PULL_REQUEST,
    OperationCardPayloadKeys.PUSH,
    OperationCardPayloadKeys.REPLIES,
    OperationCardPayloadKeys.THREADS,
  )

private val PR_REVIEW_FIX_MODES = setOf("local", "remote")

private val THREAD_KEYS =
  setOf(OperationCardPayloadKeys.ORDINAL, OperationCardPayloadKeys.THREAD_ID, OperationCardPayloadKeys.OPTIONS)

private val OPTION_KEYS =
  setOf(OperationCardPayloadKeys.OPTION_ID, OperationCardPayloadKeys.LABEL, OperationCardPayloadKeys.RECOMMENDED)

private fun cardObject(card: OperationCardProjection): JsonObject =
  JsonObject(
    linkedMapOf(
      OperationCardPayloadKeys.CONTRACT_VERSION to JsonPrimitive(card.contractVersion),
      OperationCardPayloadKeys.LAUNCH_ID to JsonPrimitive(card.launchId),
      OperationCardPayloadKeys.OPERATION_ID to JsonPrimitive(card.operationId),
      OperationCardPayloadKeys.INVOCATION_ID to JsonPrimitive(card.invocationId),
      OperationCardPayloadKeys.REPOSITORY_IDENTITY to JsonPrimitive(card.repositoryIdentity),
      OperationCardPayloadKeys.DATABASE_IDENTITY to JsonPrimitive(card.databaseIdentity),
      OperationCardPayloadKeys.CHECKOUT_ROOT to JsonPrimitive(card.checkoutRoot),
      OperationCardPayloadKeys.BRANCH to JsonPrimitive(card.branch),
      OperationCardPayloadKeys.SOURCE_ANCHORS to JsonObject(card.sourceAnchors.mapValues { JsonPrimitive(it.value) }),
      OperationCardPayloadKeys.ORIGINAL_ARGUMENTS to JsonArray(card.originalArguments.map(::JsonPrimitive)),
      OperationCardPayloadKeys.SELECTED_AGENT to JsonPrimitive(card.selectedAgent),
      OperationCardPayloadKeys.SELECTED_PROFILE to JsonPrimitive(card.selectedProfile),
      OperationCardPayloadKeys.TOKEN to JsonPrimitive(card.token),
      OperationCardPayloadKeys.FULL_SUMMARY to JsonPrimitive(card.fullSummary),
      OperationCardPayloadKeys.PROPOSAL_DETAILS to detailsObject(card.proposalDetails),
      OperationCardPayloadKeys.STATUS to JsonPrimitive(card.status.wireValue),
      OperationCardPayloadKeys.CONFIRMATION_INVOCATION_ID to JsonPrimitive(card.confirmationInvocationId),
      OperationCardPayloadKeys.SELECTION to JsonPrimitive(card.selection),
      OperationCardPayloadKeys.EVIDENCE to JsonPrimitive(card.evidence),
    ),
  )

private fun detailsObject(details: OperationCardDetails): JsonObject =
  when (details) {
    is OperationCardDetails.Verify ->
      JsonObject(
        linkedMapOf(
          OperationCardPayloadKeys.KIND to JsonPrimitive(OperationCardOperationIds.VERIFY),
          OperationCardPayloadKeys.CRITERIA to JsonArray(details.criteria.map(::JsonPrimitive)),
          OperationCardPayloadKeys.INTAKE to JsonPrimitive(details.intake),
          OperationCardPayloadKeys.SPEC to JsonPrimitive(details.spec),
          OperationCardPayloadKeys.TARGET to JsonPrimitive(details.target),
          OperationCardPayloadKeys.MODE to JsonPrimitive(details.mode),
          OperationCardPayloadKeys.QA to JsonPrimitive(details.qa),
        ),
      )
    is OperationCardDetails.PrReviewFix ->
      JsonObject(
        linkedMapOf(
          OperationCardPayloadKeys.KIND to JsonPrimitive(OperationCardOperationIds.PR_REVIEW_FIX),
          OperationCardPayloadKeys.MODE to JsonPrimitive(details.mode),
          OperationCardPayloadKeys.PULL_REQUEST to JsonPrimitive(details.pullRequest),
          OperationCardPayloadKeys.PUSH to JsonPrimitive(details.push),
          OperationCardPayloadKeys.REPLIES to JsonPrimitive(details.replies),
          OperationCardPayloadKeys.THREADS to JsonArray(details.threads.map(::threadObject)),
        ),
      )
  }

private fun threadObject(thread: OperationCardThread): JsonObject =
  JsonObject(
    linkedMapOf(
      OperationCardPayloadKeys.ORDINAL to JsonPrimitive(thread.ordinal),
      OperationCardPayloadKeys.THREAD_ID to JsonPrimitive(thread.threadId),
      OperationCardPayloadKeys.OPTIONS to
        JsonArray(
          thread.options.map { option ->
            JsonObject(
              linkedMapOf(
                OperationCardPayloadKeys.OPTION_ID to JsonPrimitive(option.optionId),
                OperationCardPayloadKeys.LABEL to JsonPrimitive(option.label),
                OperationCardPayloadKeys.RECOMMENDED to JsonPrimitive(option.recommended),
              ),
            )
          },
        ),
    ),
  )

private fun cardRecord(element: JsonElement): OperationCardProjection {
  val fields = strictObject(element, CARD_KEYS, "operation card")
  val operationId = fields.requiredText(OperationCardPayloadKeys.OPERATION_ID)
  val details = details(fields[OperationCardPayloadKeys.PROPOSAL_DETAILS])
  if (operationId !in OperationCardOperationIds.ALL || details.operationId != operationId) {
    throw SerializationException("Operation card operation $operationId does not match its proposal details")
  }
  return OperationCardProjection(
    contractVersion = fields.requiredText(OperationCardPayloadKeys.CONTRACT_VERSION),
    launchId = fields.requiredText(OperationCardPayloadKeys.LAUNCH_ID),
    operationId = operationId,
    invocationId = fields.requiredText(OperationCardPayloadKeys.INVOCATION_ID),
    repositoryIdentity = fields.requiredText(OperationCardPayloadKeys.REPOSITORY_IDENTITY),
    databaseIdentity = fields.requiredText(OperationCardPayloadKeys.DATABASE_IDENTITY),
    checkoutRoot = fields.requiredText(OperationCardPayloadKeys.CHECKOUT_ROOT),
    branch = fields.requiredText(OperationCardPayloadKeys.BRANCH),
    sourceAnchors = textMap(fields[OperationCardPayloadKeys.SOURCE_ANCHORS]),
    originalArguments = textList(fields[OperationCardPayloadKeys.ORIGINAL_ARGUMENTS], "original arguments"),
    selectedAgent = fields.requiredText(OperationCardPayloadKeys.SELECTED_AGENT),
    selectedProfile = fields.optionalText(OperationCardPayloadKeys.SELECTED_PROFILE),
    token = fields.requiredText(OperationCardPayloadKeys.TOKEN),
    fullSummary = fields.requiredText(OperationCardPayloadKeys.FULL_SUMMARY),
    proposalDetails = details,
    status =
      OperationCardStatus.fromWire(fields.requiredText(OperationCardPayloadKeys.STATUS))
        ?: throw SerializationException("Unknown operation card status"),
    confirmationInvocationId = fields.optionalText(OperationCardPayloadKeys.CONFIRMATION_INVOCATION_ID),
    selection = fields.optionalText(OperationCardPayloadKeys.SELECTION),
    evidence = fields.optionalText(OperationCardPayloadKeys.EVIDENCE),
  )
}

private fun details(element: JsonElement?): OperationCardDetails {
  val kind =
    ((element as? JsonObject)?.get(OperationCardPayloadKeys.KIND) as? JsonPrimitive)?.takeIf { it.isString }?.content
  return when (kind) {
    OperationCardOperationIds.VERIFY -> verifyDetails(strictObject(element, VERIFY_KEYS, "verify details"))
    OperationCardOperationIds.PR_REVIEW_FIX ->
      prReviewFixDetails(strictObject(element, PR_REVIEW_FIX_KEYS, "pr-review-fix details"))
    else -> throw SerializationException("Operation card proposal details have no supported kind")
  }
}

private fun verifyDetails(fields: JsonObject): OperationCardDetails.Verify =
  OperationCardDetails.Verify(
    criteria = textList(fields[OperationCardPayloadKeys.CRITERIA], "criteria"),
    intake = fields.requiredText(OperationCardPayloadKeys.INTAKE),
    spec = fields.optionalText(OperationCardPayloadKeys.SPEC),
    target = fields.requiredText(OperationCardPayloadKeys.TARGET),
    mode = fields.requiredText(OperationCardPayloadKeys.MODE),
    qa = fields.requiredText(OperationCardPayloadKeys.QA),
  )

private fun prReviewFixDetails(fields: JsonObject): OperationCardDetails.PrReviewFix {
  val threads = elements(fields[OperationCardPayloadKeys.THREADS], "threads").map(::thread)
  if (threads.isEmpty()) throw SerializationException("pr-review-fix details need at least one thread")
  val mode = fields.requiredText(OperationCardPayloadKeys.MODE)
  if (mode !in PR_REVIEW_FIX_MODES) {
    throw SerializationException(
      "pr-review-fix mode $mode is not one of $PR_REVIEW_FIX_MODES",
    )
  }
  return OperationCardDetails.PrReviewFix(
    mode = mode,
    pullRequest = fields.optionalText(OperationCardPayloadKeys.PULL_REQUEST),
    push = fields.optionalText(OperationCardPayloadKeys.PUSH),
    replies = fields.optionalText(OperationCardPayloadKeys.REPLIES),
    threads = threads,
  )
}

private fun thread(element: JsonElement): OperationCardThread {
  val fields = strictObject(element, THREAD_KEYS, "operation card thread")
  val options = elements(fields[OperationCardPayloadKeys.OPTIONS], "options").map(::option)
  if (options.isEmpty()) throw SerializationException("A thread needs at least one option")
  return OperationCardThread(
    ordinal = fields.requiredText(OperationCardPayloadKeys.ORDINAL),
    threadId = fields.requiredText(OperationCardPayloadKeys.THREAD_ID),
    options = options,
  )
}

private fun option(element: JsonElement): OperationCardOption {
  val fields = strictObject(element, OPTION_KEYS, "operation card option")
  val recommended =
    (fields[OperationCardPayloadKeys.RECOMMENDED] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
      ?: throw SerializationException("Option recommended must be a boolean")
  return OperationCardOption(
    optionId = fields.requiredText(OperationCardPayloadKeys.OPTION_ID),
    label = fields.requiredText(OperationCardPayloadKeys.LABEL),
    recommended = recommended,
  )
}

private fun elements(
  element: JsonElement?,
  what: String,
): JsonArray = element as? JsonArray ?: throw SerializationException("Operation card $what must be an array")

private fun textList(
  element: JsonElement?,
  what: String,
): List<String> =
  elements(element, what).map { item ->
    (item as? JsonPrimitive)?.takeIf { it.isString }?.content
      ?: throw SerializationException("Operation card $what entries must be strings")
  }

private fun textMap(element: JsonElement?): Map<String, String> =
  (element as? JsonObject ?: throw SerializationException("Operation card anchors must be an object"))
    .mapValues { (_, value) ->
      (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("Operation card anchors must be strings")
    }

private fun strictObject(
  element: JsonElement?,
  allowedKeys: Set<String>,
  what: String,
): JsonObject {
  val fields = element as? JsonObject ?: throw SerializationException("The $what must be a JSON object")
  val unknown = fields.keys - allowedKeys
  val missing = allowedKeys - fields.keys
  val problem =
    when {
      unknown.isNotEmpty() -> "has unknown keys $unknown"
      missing.isNotEmpty() -> "is missing keys $missing"
      else -> return fields
    }
  throw SerializationException("The $what $problem")
}

private fun JsonObject.requiredText(key: String): String =
  optionalText(key)?.takeIf(String::isNotEmpty) ?: throw SerializationException("Operation card field $key is required")

private fun JsonObject.optionalText(key: String): String? {
  val value = this[key] ?: return null
  if (value is JsonNull) return null
  val primitive = value as? JsonPrimitive
  if (primitive == null || !primitive.isString) {
    throw SerializationException("Operation card field $key must be a string")
  }
  return primitive.content
}
