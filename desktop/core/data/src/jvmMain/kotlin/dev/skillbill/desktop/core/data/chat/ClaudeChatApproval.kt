package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.approval.ApprovalGeneration
import dev.skillbill.desktop.core.data.approval.SessionApprovalGenerations
import dev.skillbill.desktop.core.data.approval.toCard
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.tatarka.inject.annotations.Inject
import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalExecutionMode
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.DENY_OPTION_KIND
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.approval.model.PROVIDER_REMEMBER_OPTION_KIND
import skillbill.contracts.CanonicalJson
import skillbill.contracts.approval.ApprovalAuthorityScope
import skillbill.contracts.approval.ApprovalOption
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.infrastructure.launcher.process.codex.CodexApprovalRequests
import skillbill.infrastructure.launcher.process.cursor.CursorAcpSession
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerKey
import skillbill.ports.approval.model.ApprovalOwnerOpenRequest
import skillbill.ports.approval.model.ApprovalOwnerOpenResult
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Base64

internal sealed interface ChatApprovalOpening {
  data class Opened(val approval: ClaudeChatApproval) : ChatApprovalOpening

  data class Refused(val reason: String) : ChatApprovalOpening
}

/** Opens the broker owner generation a live chat process needs before it may start. */
internal fun interface ChatApprovalOpener {
  fun open(
    sessionId: String,
    repositoryRoot: String,
    mode: ChatPermissionMode,
    profileIdentity: String,
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ): ChatApprovalOpening
}

/**
 * Opens one broker owner generation per live chat process. With no open database session there is no broker and no
 * operator surface, so the chat is refused rather than started without a way to answer its permission requests.
 */
@Inject
@SingleIn(AppScope::class)
class ChatApprovalAuthority(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
) {
  internal fun open(
    sessionId: String,
    repositoryRoot: String,
    mode: ChatPermissionMode,
    profileIdentity: String,
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ): ChatApprovalOpening {
    // The generation belongs to the session component that was current when it opened, and ends with that session.
    val component = sessions.sessions.value ?: return ChatApprovalOpening.Refused(NO_SESSION_REASON)
    val approval = ClaudeChatApproval(repositoryRoot, writeLine, stopProcess, diagnostics, onCard)
    val request =
      ApprovalOwnerOpenRequest(
        checkoutRoot = Path.of(repositoryRoot),
        runId = sessionId,
        provider = CHAT_APPROVAL_PROVIDER,
        executionMode = if (mode == ChatPermissionMode.ReadOnly) ApprovalExecutionMode.READ_ONLY else ApprovalExecutionMode.ACT,
        profileIdentity = profileIdentity,
        enclosingDeadline = null,
      )
    val refusal = openOwnerGeneration(
      component = component,
      request = request,
      effects = approval,
      bindAndAdmit = { owner, generations ->
        approval.bind(owner)
        approval.admitTo(generations)
      },
      release = approval::release,
    )
    return if (refusal == null) {
      ChatApprovalOpening.Opened(approval)
    } else {
      ChatApprovalOpening.Refused(refusal)
    }
  }

  internal fun openCodex(
    sessionId: String,
    repositoryRoot: String,
    mode: ChatPermissionMode,
    profileIdentity: String,
    writeLine: (String) -> Boolean,
    stopProcess: () -> Unit,
    onCard: (ApprovalCard) -> Unit,
  ): CodexChatApprovalOpening {
    if (profileIdentity.isBlank()) {
      return CodexChatApprovalOpening.Refused(
        "Codex approval scope is unavailable because its profile identity is unknown.",
      )
    }
    val component = sessions.sessions.value ?: return CodexChatApprovalOpening.Refused(NO_SESSION_REASON)
    val approval = CodexChatApproval(writeLine, stopProcess, diagnostics, onCard, repositoryRoot, profileIdentity)
    val request = ApprovalOwnerOpenRequest(
      checkoutRoot = Path.of(repositoryRoot),
      runId = sessionId,
      provider = CODEX_CHAT_APPROVAL_PROVIDER,
      executionMode =
        if (mode == ChatPermissionMode.ReadOnly) ApprovalExecutionMode.READ_ONLY else ApprovalExecutionMode.ACT,
      profileIdentity = profileIdentity,
      enclosingDeadline = null,
    )
    val refusal = openOwnerGeneration(
      component = component,
      request = request,
      effects = approval,
      bindAndAdmit = { owner, generations ->
        approval.bind(owner)
        approval.admitTo(generations)
      },
      release = approval::release,
    )
    return if (refusal == null) {
      CodexChatApprovalOpening.Opened(approval)
    } else {
      CodexChatApprovalOpening.Refused(refusal)
    }
  }

  internal fun openCursor(
    request: ApprovalOwnerOpenRequest,
    protocol: CursorAcpSession,
    stopProcess: () -> Unit,
  ): CursorChatApprovalOpening {
    if (request.profileIdentity.isBlank()) return CursorChatApprovalOpening.Refused(
      "Cursor approval scope is unavailable because its profile identity is unknown.",
    )
    val component = sessions.sessions.value ?: return CursorChatApprovalOpening.Refused(NO_SESSION_REASON)
    val generation = CursorChatGeneration(protocol, stopProcess, diagnostics)
    val refusal = openOwnerGeneration(component, request, generation, { owner, generations ->
      protocol.bind(owner)
      generation.admit(generations)
    }, generation::end)
    return if (refusal == null) CursorChatApprovalOpening.Opened(generation)
    else CursorChatApprovalOpening.Refused(refusal)
  }

  private fun openOwnerGeneration(
    component: DatabaseSessionComponent,
    request: ApprovalOwnerOpenRequest,
    effects: ApprovalOwnerEffects,
    bindAndAdmit: (ApprovalOwnerSession, SessionApprovalGenerations) -> Boolean,
    release: () -> Unit,
  ): String? =
    when (val opened = component.runtime.approvalOwners.open(request, effects)) {
      is ApprovalOwnerOpenResult.Opened -> if (bindAndAdmit(opened.session, component.approvalGenerations)) {
        null
      } else {
        release()
        SESSION_CHANGED_REASON
      }
      is ApprovalOwnerOpenResult.Refused -> opened.reason
    }

  private companion object {
    const val NO_SESSION_REASON =
      "Operator approval is unavailable: no database session is open, so no approval card can be shown."
  }
}

internal const val SESSION_CHANGED_REASON = "The database session changed while the chat was starting; send the message again."

internal const val CHAT_APPROVAL_PROVIDER = "claude"
internal const val CODEX_CHAT_APPROVAL_PROVIDER = "codex"

internal sealed interface CodexChatApprovalOpening {
  data class Opened(val approval: CodexChatApproval) : CodexChatApprovalOpening
  data class Refused(val reason: String) : CodexChatApprovalOpening
}

internal class CodexChatApproval(
  writeLine: (String) -> Boolean,
  private val stopProcess: () -> Unit,
  diagnostics: Diagnostics,
  onCard: (ApprovalCard) -> Unit,
  repositoryRoot: String,
  profileIdentity: String,
) : ApprovalOwnerEffects, ApprovalGeneration {
  private val generations = AtomicReference<SessionApprovalGenerations?>()
  private val ended = AtomicBoolean(false)
  private val stopping = AtomicBoolean(false)
  private val requests = CodexApprovalRequests(repositoryRoot, { writeLine(it.toString()) },
    { onCard(it.toCard()) }, ::stop, { diagnostics.record("chat.codex_approval_diagnostic") })

  fun bind(session: ApprovalOwnerSession) = requests.bind(session)
  fun admitTo(value: SessionApprovalGenerations): Boolean { generations.set(value); return value.admit(this) }
  fun bindTurn(thread: String, turn: String) = requests.bindTurn(thread, turn)
  fun handleServerRequest(message: JsonObject, itemFacts: Map<CodexChatItemIdentity, JsonObject>) {
    val params = message.obj("params")
    val key = params?.let { codexItemKey(it.string("threadId").orEmpty(), it.string("turnId").orEmpty(), it.string("itemId").orEmpty()) }
    itemFacts[key]?.let { requests.itemStarted(requireNotNull(params), it) }
    requests.request(message)
  }
  fun providerResolved(params: JsonObject) = requests.providerResolved(params)
  fun itemCompleted(params: JsonObject, item: JsonObject): Boolean? = requests.itemCompleted(params, item)
  fun turnEnded(thread: String, turn: String) = requests.turnEnded(thread, turn)
  fun release() {
    if (!ended.compareAndSet(false, true)) return
    generations.getAndSet(null)?.remove(this)
    requests.release()
  }
  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState = requests.deliver(settled)
  override fun stop(reason: String) {
    if (!stopping.compareAndSet(false, true)) return
    stopProcess()
    release()
  }
  override fun end() { release(); stopProcess() }
}

internal class ClaudeChatApproval(
  private val repositoryRoot: String,
  private val writeLine: (String) -> Boolean,
  private val stopProcess: () -> Unit,
  private val diagnostics: Diagnostics,
  private val onCard: (ApprovalCard) -> Unit,
) : ApprovalOwnerEffects, ApprovalGeneration {
  private val owner = AtomicReference<ApprovalOwnerSession?>()
  private val admittedTo = AtomicReference<SessionApprovalGenerations?>()
  private val ended = AtomicBoolean(false)

  /** Request ids the provider asked to be answered by a user interaction a card cannot supply. */
  private val interactionRequestIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /** True once the generation was released, by the process ending or by its database session closing. */
  val isReleased: Boolean get() = ended.get()

  fun bind(session: ApprovalOwnerSession) {
    owner.set(session)
  }

  /** Registers this generation with the database session it opened in; false when that session is already closing. */
  fun admitTo(generations: SessionApprovalGenerations): Boolean {
    admittedTo.set(generations)
    return generations.admit(this)
  }

  /** Database-session close: releases the owner session, cancelling its requests, then stops the process it served. */
  override fun end() {
    release()
    stopProcess()
  }

  fun initializeFrame(): String = frame(
    buildJsonObject {
      put("type", "control_request")
      put("request_id", INITIALIZE_REQUEST_ID)
      putJsonObject("request") { put("subtype", "initialize") }
    },
  )

  /** Reads the answer to the initialize request; every other control response is unrelated to the handshake. */
  fun handleControlResponse(root: JsonObject): ChatInitializeOutcome {
    val body = root["response"] as? JsonObject ?: return ChatInitializeOutcome.Unrelated
    if (body.text("request_id") != INITIALIZE_REQUEST_ID) return ChatInitializeOutcome.Unrelated
    if (body.text("subtype") == "success") return ChatInitializeOutcome.Initialized
    val detail = body.text("error").orEmpty()
    return ChatInitializeOutcome.Refused(
      "The installed Claude CLI refused host permission control during initialize: $detail " +
        "Update Claude Code; the chat was not started.",
    )
  }

  /** Handles one `control_request` line; a denial that cannot be written stops the process. */
  fun handle(root: JsonObject, line: String) {
    val requestId = root["request_id"] as? JsonPrimitive
    if (requestId == null) {
      stop("A provider control request carried no request id.")
      return
    }
    val body = root["request"] as? JsonObject
    if (body == null || body.text("subtype") != PERMISSION_SUBTYPE) {
      val unsupported = frame(
        buildJsonObject {
          put("type", "control_response")
          putJsonObject("response") {
            put("subtype", "error")
            put("request_id", requestId)
            put("error", "Unsupported control request.")
          }
        },
      )
      // An unanswered control request would leave the provider waiting forever, so a failed answer ends the chat.
      if (!writeLine(unsupported)) stop("An unsupported control request could not be answered.")
      return
    }
    val toolName = body.text("tool_name")
    val input = body["input"] as? JsonObject
    if (toolName.isNullOrBlank() || input == null) {
      deny(requestId, "The permission request was malformed, so the action was not run.")
      return
    }
    val session = owner.get()
    if (session == null) {
      deny(requestId, UNAVAILABLE_MESSAGE)
      return
    }
    val interaction = classify(toolName).first == ApprovalActionClass.PROVIDER_INTERACTION
    if (interaction) interactionRequestIds.add(requestId.content)
    try {
      val registered = session.register(command(session.key, requestId.content, PermissionFrame(toolName, input, body, line)))
      showCard(registered)
      if (interaction && registered is ApprovalRegistrationResult.Registered) {
        // The tool needs a user interaction no card can supply: the provider-required state wins over any grant.
        session.resolveByProvider(
          registered.request.approvalId,
          registered.request.providerRequestId,
          ApprovalProviderDisposition.INTERACTION_REQUIRED,
        )
      }
    } catch (_: RuntimeException) {
      diagnostics.record("chat.approval_register_failed")
      deny(requestId, UNAVAILABLE_MESSAGE)
    }
  }

  /**
   * Emits the registered request, whatever its first outcome, as a typed card event so the chat shows it at once. The
   * card carries no grant eligibility: its controls stay disabled until the operator feed supplies the runtime's answer.
   */
  private fun showCard(registered: ApprovalRegistrationResult) {
    val request = when (registered) {
      is ApprovalRegistrationResult.Registered -> registered.request
      is ApprovalRegistrationResult.AutoAllowed -> registered.request
      is ApprovalRegistrationResult.Refused -> registered.request
      ApprovalRegistrationResult.IdentityConflict -> return
    }
    onCard(request.toCard())
  }

  /** Normal closure: cancels this generation's outstanding requests; the reader calls it when the process ends. */
  fun release() {
    ended.set(true)
    admittedTo.get()?.remove(this)
    val session = owner.getAndSet(null) ?: return
    try {
      session.release()
    } catch (_: RuntimeException) {
      diagnostics.record("chat.approval_release_failed")
    }
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState {
    val requestId: JsonElement =
      when (val id = settled.providerRequestId) {
        is ApprovalProviderRequestId.Text -> JsonPrimitive(id.value)
        is ApprovalProviderRequestId.Integer -> JsonPrimitive(id.value)
      }
    // The stored provider payload is the complete control_request frame; its request.input is the original input.
    val original = if (settled.state == ApprovalState.ALLOWED) originalInput(settled.providerPayload) else null
    val line =
      when {
        settled.state == ApprovalState.ALLOWED && original != null -> response(requestId) {
          put("behavior", "allow")
          put("updatedInput", original)
        }
        settled.state == ApprovalState.ALLOWED -> denyFrame(requestId, "The approved input could not be restored, so the action was not run.")
        (requestId as? JsonPrimitive)?.content in interactionRequestIds -> denyFrame(requestId, INTERACTION_MESSAGE)
        else -> denyFrame(requestId, denialMessage(settled.state))
      }
    return if (writeLine(line)) ApprovalDeliveryState.WRITTEN else ApprovalDeliveryState.UNCERTAIN
  }

  override fun stop(reason: String) {
    diagnostics.record("chat.approval_channel_stopped")
    stopProcess()
  }

  private fun deny(requestId: JsonElement, message: String) {
    if (!writeLine(denyFrame(requestId, message))) stop("A denial could not be delivered to the provider.")
  }

  private fun denyFrame(requestId: JsonElement, message: String): String = response(requestId) {
    put("behavior", "deny")
    put("message", message)
  }

  private fun response(requestId: JsonElement, body: JsonObjectBuilder.() -> Unit): String = frame(
    buildJsonObject {
      put("type", "control_response")
      putJsonObject("response") {
        put("subtype", "success")
        put("request_id", requestId)
        putJsonObject("response", body)
      }
    },
  )

  private fun command(key: ApprovalOwnerKey, requestId: String, frame: PermissionFrame): ApprovalRegistrationCommand {
    val toolName = frame.toolName
    val input = frame.input
    val tool = classify(toolName)
    val request = ApprovalRequestRecord(
      contractVersion = OPERATOR_APPROVAL_CONTRACT_VERSION,
      approvalId = "$CHAT_APPROVAL_PROVIDER-$requestId",
      repositoryIdentity = key.repositoryIdentity,
      checkoutRoot = repositoryRoot,
      runId = key.runId,
      processGeneration = key.processGeneration,
      provider = CHAT_APPROVAL_PROVIDER,
      providerRequestId = ApprovalProviderRequestId.Text(requestId),
      title = frame.body.text("title")?.takeIf(String::isNotBlank) ?: "Allow $toolName?",
      reason = frame.reason(),
      subject = toolName,
      inputText = PRETTY_JSON.encodeToString(JsonElement.serializer(), input),
      options = listOf(
        ApprovalOption(ALLOW_OPTION_ID, "Allow once", ONE_TIME_ALLOW_OPTION_KIND),
        ApprovalOption(DENY_OPTION_ID, "Deny", DENY_OPTION_KIND),
      ) + frame.rememberOptions(),
      authorityScope = ApprovalAuthorityScope(
        // Placeholders: the owner generation stamps the selected profile and checkout-relative directory.
        profileIdentity = "",
        protocolRevision = PROTOCOL_REVISION,
        actionKind = toolName,
        workingDirectory = "",
        actionFingerprint = SCOPE_FINGERPRINT_PLACEHOLDER,
      ),
      createdAt = Instant.now().toString(),
      deadlineAt = "",
      state = ApprovalState.PENDING,
      providerPayload = frame.line,
    )
    return ApprovalRegistrationCommand(
      request = request,
      canonicalInput = CanonicalJson.encode(input),
      actionClass = tool.first,
      writeCapable = tool.second,
      providerDisposition = if (tool.first == ApprovalActionClass.PROVIDER_INTERACTION) {
        ApprovalProviderDisposition.INTERACTION_REQUIRED
      } else {
        ApprovalProviderDisposition.NONE
      },
      enclosingDeadline = null,
    )
  }

  private fun classify(toolName: String): Pair<ApprovalActionClass, Boolean> = when {
    toolName in INTERACTION_TOOLS -> ApprovalActionClass.PROVIDER_INTERACTION to false
    toolName == BASH_TOOL -> ApprovalActionClass.ORDINARY_COMMAND to true
    toolName in NETWORK_TOOLS -> ApprovalActionClass.NETWORK_OR_CREDENTIAL to false
    toolName in READ_ONLY_TOOLS -> ApprovalActionClass.ORDINARY_TOOL to false
    else -> ApprovalActionClass.ORDINARY_TOOL to true
  }

  private fun denialMessage(state: ApprovalState): String = when (state) {
    ApprovalState.DENIED -> "The operator denied this action."
    ApprovalState.CANCELLED -> "The approval request was cancelled, so the action was not run."
    ApprovalState.EXPIRED -> "The approval request expired before the operator decided."
    ApprovalState.UNSUPPORTED -> UNAVAILABLE_MESSAGE
    ApprovalState.PROCESS_ENDED -> "The agent run ended before the request was decided."
    ApprovalState.PENDING, ApprovalState.ALLOWED -> "The request was not approved."
  }

  private fun originalInput(providerPayload: String): JsonObject? =
    (parseStreamLine(providerPayload)?.get("request") as? JsonObject)?.get("input") as? JsonObject

  private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun frame(element: JsonElement): String = element.toString()

  private companion object {
    const val PROTOCOL_REVISION = "stream-json-1"
    const val INITIALIZE_REQUEST_ID = "skill-bill-init"
    const val PERMISSION_SUBTYPE = "can_use_tool"
    const val ALLOW_OPTION_ID = "allow_once"
    const val DENY_OPTION_ID = "deny"
    const val SCOPE_FINGERPRINT_PLACEHOLDER = "pending"
    const val BASH_TOOL = "Bash"
    val PRETTY_JSON = Json { prettyPrint = true }
    val INTERACTION_TOOLS = setOf("AskUserQuestion", "ExitPlanMode")
    const val INTERACTION_MESSAGE =
      "This tool needs interactive input that the approval card cannot supply; ask the operator in your reply " +
        "instead. In read-only chat, switch to Act to leave plan mode."
    const val UNAVAILABLE_MESSAGE =
      "Operator approval is unavailable for this request: no operator surface can answer it, so the action was not run."
    val NETWORK_TOOLS = setOf("WebFetch", "WebSearch")
    val READ_ONLY_TOOLS = setOf("Read", "Glob", "Grep", "LS", "TodoWrite")
  }
}

internal fun codexItemKey(threadId: String, turnId: String, itemId: String): CodexChatItemIdentity =
  CodexChatItemIdentity(threadId, turnId, itemId)

internal data class CodexChatItemIdentity(val threadId: String, val turnId: String, val itemId: String)

internal sealed interface ChatInitializeOutcome {
  data object Initialized : ChatInitializeOutcome

  data class Refused(val message: String) : ChatInitializeOutcome

  data object Unrelated : ChatInitializeOutcome
}

/** One decoded `can_use_tool` request: the typed fields plus the complete received frame line. */
private class PermissionFrame(val toolName: String, val input: JsonObject, val body: JsonObject, val line: String) {
  /** Provider description and decision reason as the card reason; null when the provider sent neither. */
  fun reason(): String? = listOf("description", "decision_reason")
    .mapNotNull { key -> body[key]?.takeUnless { it is JsonNull }?.let(::contentText) }
    .filter(String::isNotBlank)
    .joinToString("\n\n")
    .ifBlank { null }

  /**
   * The provider's own remembering choices (`permission_suggestions`), in provider order after the one-time options.
   * They are shown so every provider option is visible, but they have no grant kind: a card can never select one, and
   * the provider's complete suggestion JSON stays in the stored provider payload.
   */
  fun rememberOptions(): List<ApprovalOption> =
    (body["permission_suggestions"] as? JsonArray).orEmpty().mapIndexed { index, suggestion ->
      ApprovalOption("provider_remember_${index + 1}", suggestionLabel(suggestion), PROVIDER_REMEMBER_OPTION_KIND)
    }

  private fun suggestionLabel(suggestion: JsonElement): String {
    val entry = suggestion as? JsonObject ?: return suggestion.toString()
    val rules = (entry["rules"] as? JsonArray).orEmpty().joinToString(", ") { rule ->
      val item = rule as? JsonObject
      listOfNotNull(
        (item?.get("toolName") as? JsonPrimitive)?.contentOrNull,
        (item?.get("ruleContent") as? JsonPrimitive)?.contentOrNull?.let { "($it)" },
      ).joinToString("").ifBlank { rule.toString() }
    }
    val detail = listOfNotNull(
      (entry["behavior"] as? JsonPrimitive)?.contentOrNull,
      rules.ifBlank { null },
      (entry["mode"] as? JsonPrimitive)?.contentOrNull?.let { "mode $it" },
    ).joinToString(" ")
    val destination = (entry["destination"] as? JsonPrimitive)?.contentOrNull?.let { " in $it" }.orEmpty()
    val type = (entry["type"] as? JsonPrimitive)?.contentOrNull ?: "suggestion"
    return "Provider remember: $type${detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}$destination"
  }

  private fun contentText(element: JsonElement): String = (element as? JsonPrimitive)?.content ?: element.toString()
}
