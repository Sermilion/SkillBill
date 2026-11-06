package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.sameApprovalRegistrationPayload
import skillbill.contracts.CanonicalJson
import skillbill.contracts.approval.ApprovalAuthorityScope
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

class CodexApprovalRequests(
  private val checkout: String,
  private val write: (JsonObject) -> Boolean,
  private val onCard: (ApprovalRequestRecord) -> Unit,
  private val stop: (String) -> Unit,
  private val record: (String) -> Unit,
) {
  private val owner = AtomicReference<ApprovalOwnerSession?>()
  private val lock = Any()
  private var thread = ""
  private var turn = ""
  private val pending = LinkedHashMap<ApprovalProviderRequestId, CodexPendingApproval>()
  private val consumed = LinkedHashMap<ApprovalProviderRequestId, String>()
  private val cleared = mutableSetOf<ApprovalProviderRequestId>()
  private val items = LinkedHashMap<CodexItemIdentity, JsonObject>()
  private val terminalItems = LinkedHashMap<CodexItemIdentity, JsonObject>()
  private val unsupported = CodexUnsupportedApprovals(checkout, owner::get, write, onCard, stop)

  fun bind(session: ApprovalOwnerSession) {
    owner.set(session)
  }

  fun bindTurn(
    threadId: String,
    turnId: String,
  ) {
    thread = threadId
    turn = turnId
  }

  fun pendingWait(): ApprovalPendingWait? = owner.get()?.pendingWait()

  fun itemStarted(
    params: JsonObject,
    item: JsonObject,
  ) {
    val key = itemIdentity(params, item.text(K.ID)) ?: return
    val failure =
      synchronized(lock) {
        val previous = items[key]
        val terminal = terminalItems[key]
        when {
          terminal != null && terminal != item -> "Codex changed a terminal item's immutable action facts."
          terminal != null -> null
          previous != null && previous != item -> "Codex changed an item's immutable action facts."
          items.size >= MAX_ITEMS && previous == null -> "Codex exceeded item retention capacity."
          else -> {
            items[key] = item
            null
          }
        }
      }
    failure?.let(stop)
  }

  fun request(message: JsonObject) {
    val method = message.text(K.METHOD) ?: return stop("Codex sent a blocking request without a method.")
    val params = message.obj(K.PARAMS) ?: return stop("Codex sent malformed approval parameters.")
    val supported = method in SUPPORTED_METHODS && params[K.NETWORK_APPROVAL_CONTEXT].nonNull() == null
    if (!supported) {
      unsupported.handle(message, thread, turn)
      return
    }
    val identity = itemIdentity(params, params.text(K.ITEM_ID))
    val id = message[K.ID].providerId()
    if (identity == null || id == null) return stop("Codex approval does not match the active thread, turn and item.")
    if (synchronized(lock) { identity in terminalItems }) {
      stop("Codex requested approval for an item that has already completed.")
    } else {
      register(message, params, identity, id, method)
    }
  }

  private fun register(
    message: JsonObject,
    params: JsonObject,
    identity: CodexItemIdentity,
    id: ApprovalProviderRequestId,
    method: String,
  ) {
    val snapshot = CanonicalJson.encode(message)
    if (!admitIdentity(id, snapshot)) return
    val session = owner.get() ?: return stop("Codex approval has no live operator channel.")
    val started = synchronized(lock) { items[identity] }
    val action = CodexApprovalActions.decode(method, params, started)
    val request =
      record(
        session,
        id,
        params,
        action,
        buildJsonObject {
          put(K.REQUEST, message)
          started?.let { put(K.ITEM_STARTED, it) }
        }.toString(),
      )
    val waiting =
      CodexPendingApproval(
        request,
        action.canonical,
        action.complete,
        CodexApprovalActions.decisions(params).mapNotNull(CodexApprovalActions::decisionName),
        identity,
      )
    synchronized(lock) { pending[id] = waiting }
    val result =
      catchingNonCancellation {
        session.register(
          ApprovalRegistrationCommand(
            request,
            action.canonical,
            action.actionClass,
            true,
            ApprovalProviderDisposition.NONE,
            null,
          ),
        )
      }.getOrElse { return stop("Codex approval registration failed: ${it.message.orEmpty()}") }
    show(result)
    if (!action.complete) {
      session.resolveByProvider(request.approvalId, id, ApprovalProviderDisposition.INTERACTION_REQUIRED)
    }
  }

  private fun record(
    session: ApprovalOwnerSession,
    id: ApprovalProviderRequestId,
    params: JsonObject,
    action: CodexApprovalAction,
    payload: String,
  ): ApprovalRequestRecord =
    ApprovalRequestRecord(
      contractVersion = OPERATOR_APPROVAL_CONTRACT_VERSION,
      approvalId = approvalId(session.key.processGeneration, id),
      repositoryIdentity = session.key.repositoryIdentity,
      checkoutRoot = checkout,
      runId = session.key.runId,
      processGeneration = session.key.processGeneration,
      provider = "codex",
      providerRequestId = id,
      providerSessionId = thread,
      title = params.text(K.TITLE)?.takeIf(String::isNotBlank) ?: "Codex permission request",
      reason = params.text(K.REASON),
      subject = action.subject,
      inputText = action.display,
      options = CodexApprovalActions.options(params, action.complete),
      authorityScope =
        ApprovalAuthorityScope(
          "",
          "codex-app-server-$CODEX_PROTOCOL_VERSION",
          action.kind,
          "",
          "pending",
        ),
      createdAt = "",
      deadlineAt = "",
      state = ApprovalState.PENDING,
      providerPayload = payload,
    )

  private fun admitIdentity(
    id: ApprovalProviderRequestId,
    snapshot: String,
  ): Boolean {
    var failure: String? = null
    val admitted =
      synchronized(lock) {
        val previous = consumed[id]
        when {
          previous != null -> {
            if (previous != snapshot) failure = "Codex reused an approval id with changed content."
            false
          }
          consumed.size >= MAX_IDENTITIES || pending.values.count { !it.sent } >= MAX_PENDING -> {
            failure = "Codex exceeded approval identity capacity."
            false
          }
          else -> {
            consumed[id] = snapshot
            true
          }
        }
      }
    failure?.let(stop)
    return admitted
  }

  private fun show(result: ApprovalRegistrationResult) {
    when (result) {
      is ApprovalRegistrationResult.Registered -> onCard(result.request)
      is ApprovalRegistrationResult.AutoAllowed -> onCard(result.request)
      is ApprovalRegistrationResult.Refused -> onCard(result.request)
      ApprovalRegistrationResult.IdentityConflict -> stop("Codex approval identity conflicted with retained content.")
    }
  }

  fun providerResolved(params: JsonObject) {
    if (params.text(K.THREAD_ID) != thread) return
    val id = (params[K.REQUEST_ID] ?: params[K.ID]).providerId() ?: return
    clear(ApprovalProviderClearance.RESOLVED) { it.record.providerRequestId == id }
  }

  fun itemCompleted(
    params: JsonObject,
    item: JsonObject,
  ): Boolean? {
    val identity = itemIdentity(params, item.text(K.ID)) ?: return null
    val result =
      synchronized(lock) {
        val previous = terminalItems[identity]
        when {
          previous != null && previous != item -> false to true
          previous != null -> null to false
          terminalItems.size >= MAX_TOMBSTONES -> false to true
          else -> {
            items.remove(identity)
            terminalItems[identity] = item
            pending.values.any { it.identity == identity && it.allowed } to false
          }
        }
      }
    if (result.second) {
      stop("Codex changed a completed item or exceeded terminal identity capacity.")
      return null
    }
    if (result.first == null) return null
    val cause =
      if (item.text(K.STATUS) == K.DECLINED) {
        ApprovalProviderClearance.DENIED
      } else {
        ApprovalProviderClearance.RESOLVED
      }
    clear(cause) { it.identity == identity }
    return result.first
  }

  fun turnEnded(
    threadId: String,
    turnId: String,
  ) {
    if (threadId != thread || turnId != turn) return
    unsupported.clear()
    clear(ApprovalProviderClearance.RESOLVED) { true }
    synchronized(lock) { items.clear() }
  }

  private fun clear(
    cause: ApprovalProviderClearance,
    matches: (CodexPendingApproval) -> Boolean,
  ) {
    val requests =
      synchronized(lock) {
        pending.values.filter(matches).also { values -> values.forEach { cleared += it.record.providerRequestId } }
      }
    requests.forEach { request ->
      owner.get()?.clearByProvider(request.record.approvalId, request.record.providerRequestId, cause)
    }
  }

  fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState {
    unsupported.deliver(settled)?.let { return it }
    val result =
      synchronized(lock) {
        val id = settled.providerRequestId
        val waiting = pending[id]
        when {
          id in cleared -> ApprovalDeliveryState.NOT_REQUIRED
          waiting == null || waiting.sent -> ApprovalDeliveryState.UNCERTAIN
          !waiting.matches(settled) -> ApprovalDeliveryState.UNCERTAIN
          else -> dispatch(waiting, settled)
        }
      }
    if (result == ApprovalDeliveryState.UNCERTAIN) {
      stop("Codex response delivery is unsafe or uncertain; it will not be replayed.")
    }
    return result
  }

  private fun dispatch(
    waiting: CodexPendingApproval,
    settled: ApprovalRequestRecord,
  ): ApprovalDeliveryState {
    val decision =
      when (settled.state) {
        ApprovalState.ALLOWED -> K.ACCEPT.takeIf { waiting.complete }
        ApprovalState.DENIED, ApprovalState.UNSUPPORTED -> K.DECLINE
        ApprovalState.CANCELLED, ApprovalState.EXPIRED, ApprovalState.PROCESS_ENDED -> K.CANCEL
        ApprovalState.PENDING -> null
      }
    if (decision == null || decision !in waiting.decisions) return ApprovalDeliveryState.UNCERTAIN
    waiting.sent = true
    return if (write(
        buildJsonObject {
          put(K.ID, settled.providerRequestId.providerJson())
          put(K.RESULT, buildJsonObject { put(K.PROVIDER_DECISION, decision) })
        },
      )
    ) {
      waiting.allowed = settled.state == ApprovalState.ALLOWED
      ApprovalDeliveryState.WRITTEN
    } else {
      ApprovalDeliveryState.UNCERTAIN
    }
  }

  fun release() {
    val session = owner.get() ?: return
    catchingNonCancellation { session.release() }.onFailure { record("seam=codex_approval outcome=release_failed") }
    owner.compareAndSet(session, null)
    synchronized(lock) {
      pending.clear()
      items.clear()
      terminalItems.clear()
    }
  }

  private fun itemIdentity(
    params: JsonObject,
    item: String?,
  ): CodexItemIdentity? {
    if (params.text(K.THREAD_ID) != thread || params.text(K.TURN_ID) != turn) return null
    return item?.takeIf(String::isNotBlank)?.let { CodexItemIdentity(thread, turn, it) }
  }

  private companion object {
    const val MAX_IDENTITIES = 1024
    const val MAX_ITEMS = 512
    const val MAX_PENDING = 128
    const val MAX_TOMBSTONES = 1024
    val SUPPORTED_METHODS = setOf(K.ITEM_COMMAND_APPROVAL, K.ITEM_FILE_APPROVAL)
  }
}

private data class CodexItemIdentity(val thread: String, val turn: String, val item: String)

private class CodexPendingApproval(
  val record: ApprovalRequestRecord,
  val canonical: String?,
  val complete: Boolean,
  val decisions: List<String>,
  val identity: CodexItemIdentity,
) {
  var sent = false
  var allowed = false

  fun matches(settled: ApprovalRequestRecord): Boolean {
    val scope =
      canonical?.let { fingerprint ->
        record.authorityScope?.copy(
          profileIdentity = settled.authorityScope?.profileIdentity.orEmpty(),
          workingDirectory = settled.authorityScope?.workingDirectory.orEmpty(),
          actionFingerprint = fingerprint,
        )
      }
    return sameApprovalRegistrationPayload(record.copy(actionFingerprint = canonical, authorityScope = scope), settled)
  }
}

private val INTEGER = Regex("0|-?[1-9][0-9]*")

private fun JsonElement?.providerId(): ApprovalProviderRequestId? {
  val id = this as? JsonPrimitive ?: return null
  return when {
    id.isString -> ApprovalProviderRequestId.Text(id.content)
    INTEGER.matches(id.content) -> ApprovalProviderRequestId.Integer(id.content)
    else -> null
  }
}

private fun ApprovalProviderRequestId.providerJson(): JsonElement =
  when (this) {
    is ApprovalProviderRequestId.Text -> JsonPrimitive(value)
    is ApprovalProviderRequestId.Integer -> Json.parseToJsonElement(value)
  }

private fun approvalId(
  generation: String,
  id: ApprovalProviderRequestId,
): String {
  val typed =
    when (id) {
      is ApprovalProviderRequestId.Text -> "s:${id.value}"
      is ApprovalProviderRequestId.Integer -> "i:${id.value}"
    }
  return "codex:$generation:" + Base64.getUrlEncoder().withoutPadding().encodeToString(typed.encodeToByteArray())
}

private fun JsonElement?.nonNull(): JsonElement? = takeUnless { it == JsonNull }
