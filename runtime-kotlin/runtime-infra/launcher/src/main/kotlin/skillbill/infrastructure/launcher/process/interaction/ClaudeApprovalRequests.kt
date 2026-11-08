package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.DENY_OPTION_KIND
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.contracts.CanonicalJson
import skillbill.contracts.approval.ApprovalAuthorityScope
import skillbill.contracts.approval.ApprovalOption
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

const val CLAUDE_APPROVAL_PROVIDER = "claude"

/** How the provider answered the host permission initialize request. */
sealed interface ClaudeInitializeOutcome {
  data object Initialized : ClaudeInitializeOutcome

  data class Refused(val detail: String) : ClaudeInitializeOutcome

  data object Unrelated : ClaudeInitializeOutcome
}

class ClaudeApprovalRequests(
  private val checkout: String,
  private val write: (JsonObject) -> Boolean,
  private val onCard: (ApprovalRequestRecord) -> Unit,
  private val stop: (String) -> Unit,
  private val record: (String) -> Unit,
  private val announce: (String) -> Unit = {},
  private val clock: Clock,
) {
  private val owner = AtomicReference<ApprovalOwnerSession?>()

  private val interactionRequestIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  fun bind(session: ApprovalOwnerSession) {
    owner.set(session)
  }

  fun pendingWait(): ApprovalPendingWait? = owner.get()?.pendingWait()

  fun release() {
    val session = owner.getAndSet(null) ?: return
    catchingNonCancellation { session.release() }
      .onFailure { failure -> record("Approval generation release failed: ${failure.message.orEmpty()}") }
  }

  fun initializeFrame(): JsonObject = ClaudeHostFrames.initialize(INITIALIZE_REQUEST_ID)

  fun initializeOutcome(root: JsonObject): ClaudeInitializeOutcome {
    val body = root["response"] as? JsonObject ?: return ClaudeInitializeOutcome.Unrelated
    return when {
      body.text("request_id") != INITIALIZE_REQUEST_ID -> ClaudeInitializeOutcome.Unrelated
      body.text("subtype") == "success" -> ClaudeInitializeOutcome.Initialized
      else -> ClaudeInitializeOutcome.Refused(body.text("error").orEmpty())
    }
  }

  fun handle(
    root: JsonObject,
    line: String,
  ) {
    val requestId =
      root["request_id"] as? JsonPrimitive ?: return stop("A provider control request carried no request id.")
    val body = root["request"] as? JsonObject
    if (body == null || body.text("subtype") != PERMISSION_SUBTYPE) {
      if (!write(ClaudeHostFrames.unsupported(requestId, "Unsupported control request."))) {
        stop("An unsupported control request could not be answered.")
      }
      return
    }
    val toolName = body.text("tool_name")
    val input = body["input"] as? JsonObject
    if (toolName.isNullOrBlank() || input == null) {
      deny(requestId, "The permission request was malformed, so the action was not run.")
      return
    }
    register(requestId, PermissionFrame(toolName, input, body, line))
  }

  fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState {
    val requestId = settled.providerRequestId.toJsonElement()
    val original = if (settled.state == ApprovalState.ALLOWED) originalInput(settled.providerPayload) else null
    val frame =
      when {
        original != null -> ClaudeHostFrames.allow(requestId, original)
        settled.state == ApprovalState.ALLOWED ->
          ClaudeHostFrames.deny(requestId, "The approved input could not be restored, so the action was not run.")
        (requestId as? JsonPrimitive)?.content in interactionRequestIds ->
          ClaudeHostFrames.deny(requestId, INTERACTION_MESSAGE)
        else -> ClaudeHostFrames.deny(requestId, denialMessage(settled.state))
      }
    announce("operator approval ${settled.state.wireValue}: ${settled.title}")
    return if (write(frame)) ApprovalDeliveryState.WRITTEN else ApprovalDeliveryState.UNCERTAIN
  }

  private fun register(
    requestId: JsonPrimitive,
    frame: PermissionFrame,
  ) {
    val session = owner.get() ?: return deny(requestId, UNAVAILABLE_MESSAGE)
    val tool = classify(frame.toolName)
    val interaction = tool.actionClass == ApprovalActionClass.PROVIDER_INTERACTION
    if (interaction) interactionRequestIds.add(requestId.content)
    catchingNonCancellation {
      val registered = session.register(command(session.key, requestId.content, frame, tool))
      registered.request()?.let(onCard)
      if (interaction && registered is ApprovalRegistrationResult.Registered) {
        session.resolveByProvider(
          registered.request.approvalId,
          registered.request.providerRequestId,
          ApprovalProviderDisposition.INTERACTION_REQUIRED,
        )
      }
    }.onFailure { failure ->
      record("Approval registration failed: ${failure.message.orEmpty()}")
      deny(requestId, UNAVAILABLE_MESSAGE)
    }
  }

  private fun deny(
    requestId: JsonElement,
    message: String,
  ) {
    announce("operator approval unavailable: $message")
    if (!write(ClaudeHostFrames.deny(requestId, message))) stop("A denial could not be delivered to the provider.")
  }

  private fun command(
    key: ApprovalOwnerKey,
    requestId: String,
    frame: PermissionFrame,
    tool: ToolClass,
  ): ApprovalRegistrationCommand {
    val toolName = frame.toolName
    val request =
      ApprovalRequestRecord(
        contractVersion = OPERATOR_APPROVAL_CONTRACT_VERSION,
        approvalId = "$CLAUDE_APPROVAL_PROVIDER-$requestId",
        repositoryIdentity = key.repositoryIdentity,
        checkoutRoot = checkout,
        runId = key.runId,
        processGeneration = key.processGeneration,
        provider = CLAUDE_APPROVAL_PROVIDER,
        providerRequestId = ApprovalProviderRequestId.Text(requestId),
        title = frame.body.text("title")?.takeIf(String::isNotBlank) ?: "Allow $toolName?",
        reason = frame.reason(),
        subject = toolName,
        inputText = PRETTY_JSON.encodeToString(JsonElement.serializer(), frame.input),
        options =
          listOf(
            ApprovalOption(ALLOW_OPTION_ID, "Allow once", ONE_TIME_ALLOW_OPTION_KIND),
            ApprovalOption(DENY_OPTION_ID, "Deny", DENY_OPTION_KIND),
          ) + frame.rememberOptions(),
        authorityScope =
          ApprovalAuthorityScope(
            profileIdentity = "",
            protocolRevision = PROTOCOL_REVISION,
            actionKind = toolName,
            workingDirectory = "",
            actionFingerprint = SCOPE_FINGERPRINT_PLACEHOLDER,
          ),
        createdAt = clock.instant().toString(),
        deadlineAt = "",
        state = ApprovalState.PENDING,
        providerPayload = frame.line,
      )
    return ApprovalRegistrationCommand(
      request = request,
      canonicalInput = CanonicalJson.encode(frame.input),
      actionClass = tool.actionClass,
      writeCapable = tool.writeCapable,
      providerDisposition =
        if (tool.actionClass == ApprovalActionClass.PROVIDER_INTERACTION) {
          ApprovalProviderDisposition.INTERACTION_REQUIRED
        } else {
          ApprovalProviderDisposition.NONE
        },
      enclosingDeadline = null,
    )
  }

  private fun ApprovalRegistrationResult.request(): ApprovalRequestRecord? =
    when (this) {
      is ApprovalRegistrationResult.Registered -> request
      is ApprovalRegistrationResult.AutoAllowed -> request
      is ApprovalRegistrationResult.Refused -> request
      ApprovalRegistrationResult.IdentityConflict -> null
    }

  private data class ToolClass(val actionClass: ApprovalActionClass, val writeCapable: Boolean)

  private fun classify(toolName: String): ToolClass =
    when {
      toolName in INTERACTION_TOOLS -> ToolClass(ApprovalActionClass.PROVIDER_INTERACTION, writeCapable = false)
      toolName == BASH_TOOL -> ToolClass(ApprovalActionClass.ORDINARY_COMMAND, writeCapable = true)
      toolName in NETWORK_TOOLS -> ToolClass(ApprovalActionClass.NETWORK_OR_CREDENTIAL, writeCapable = false)
      toolName in READ_ONLY_TOOLS -> ToolClass(ApprovalActionClass.ORDINARY_TOOL, writeCapable = false)
      else -> ToolClass(ApprovalActionClass.ORDINARY_TOOL, writeCapable = true)
    }

  private fun denialMessage(state: ApprovalState): String =
    when (state) {
      ApprovalState.DENIED -> "The operator denied this action."
      ApprovalState.CANCELLED -> "The approval request was cancelled, so the action was not run."
      ApprovalState.EXPIRED -> "The approval request expired before the operator decided."
      ApprovalState.UNSUPPORTED -> UNAVAILABLE_MESSAGE
      ApprovalState.PROCESS_ENDED -> "The agent run ended before the request was decided."
      ApprovalState.PENDING, ApprovalState.ALLOWED -> "The request was not approved."
    }

  private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun ApprovalProviderRequestId.toJsonElement(): JsonElement =
    when (this) {
      is ApprovalProviderRequestId.Text -> JsonPrimitive(value)
      is ApprovalProviderRequestId.Integer -> JsonPrimitive(value)
    }

  private companion object {
    const val PROTOCOL_REVISION = "stream-json-1"
    const val INITIALIZE_REQUEST_ID = "skill-bill-init"
    const val PERMISSION_SUBTYPE = "can_use_tool"
    val PRETTY_JSON = Json { prettyPrint = true }
    val INTERACTION_TOOLS = setOf("AskUserQuestion", "ExitPlanMode")
    const val INTERACTION_MESSAGE =
      "This tool needs interactive input that the approval card cannot supply; ask the operator in your reply " +
        "instead. In read-only chat, switch to Act to leave plan mode."
    const val ALLOW_OPTION_ID = "allow_once"
    const val DENY_OPTION_ID = "deny"
    const val SCOPE_FINGERPRINT_PLACEHOLDER = "pending"
    const val BASH_TOOL = "Bash"
    const val UNAVAILABLE_MESSAGE =
      "Operator approval is unavailable for this request: no operator surface can answer it, so the action was not run."
    val NETWORK_TOOLS = setOf("WebFetch", "WebSearch")
    val READ_ONLY_TOOLS = setOf("Read", "Glob", "Grep", "LS", "TodoWrite")
  }
}
