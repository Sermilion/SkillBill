package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.approval.model.sameApprovalRegistrationPayload
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import java.util.Base64
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

internal class CodexUnsupportedApprovals(
  private val checkoutRoot: String,
  private val owner: () -> ApprovalOwnerSession?,
  private val write: (JsonObject) -> Boolean,
  private val onCard: (ApprovalRequestRecord) -> Unit,
  private val stop: (String) -> Unit,
  private val record: (String) -> Unit,
) {
  private val lock = Any()
  private val entries = LinkedHashMap<ApprovalProviderRequestId, UnsupportedCodexRequest>()

  fun handle(
    message: JsonObject,
    threadId: String,
    turnId: String,
  ) {
    val id = message[K.ID].unsupportedId() ?: return stop("Codex sent an unsupported request with a malformed id.")
    val method = (message[K.METHOD] as? JsonPrimitive)?.content.orEmpty()
    val params = message[K.PARAMS] as? JsonObject ?: return stop("Codex sent malformed unsupported request parameters.")
    val session = owner()
    val body = response(method, params, allow = session == null)
    if (session == null) {
      record("seam=codex_request outcome=bypass_response method=$method")
      if (!writeResponse(id, body)) stop("Codex request response could not be delivered.")
      return
    }
    val requestThread = (params[K.THREAD_ID] as? JsonPrimitive)?.content
    val requestTurn = (params[K.TURN_ID] as? JsonPrimitive)?.content
    if (!matches(requestThread, threadId) || !matches(requestTurn, turnId)) {
      record("seam=codex_request outcome=stale_request_cancelled method=$method")
      if (!writeResponse(id, body)) stop("Codex stale request response could not be delivered.")
      return
    }
    val request = request(message, session, id, threadId)
    if (admit(request, body)) {
      register(session, request)
      session.resolveByProvider(request.approvalId, id, ApprovalProviderDisposition.INTERACTION_REQUIRED)
    }
  }

  private fun writeResponse(
    id: ApprovalProviderRequestId,
    body: JsonObject,
  ): Boolean =
    write(
      buildJsonObject {
        put(K.ID, id.unsupportedJson())
        body.forEach { (key, value) -> put(key, value) }
      },
    )

  private fun request(
    message: JsonObject,
    session: ApprovalOwnerSession,
    id: ApprovalProviderRequestId,
    threadId: String,
  ): ApprovalRequestRecord {
    val params = requireNotNull(message[K.PARAMS] as? JsonObject)
    val method = (message[K.METHOD] as? JsonPrimitive)?.content.orEmpty()
    val explanation =
      "Unsupported Codex permission family '$method'; this integration cannot " +
        "approve it."
    val typed =
      when (id) {
        is ApprovalProviderRequestId.Text -> "s:${id.value}"
        is ApprovalProviderRequestId.Integer -> "i:${id.value}"
      }
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(typed.toByteArray(Charsets.UTF_8))
    val request =
      ApprovalRequestRecord(
        OPERATOR_APPROVAL_CONTRACT_VERSION,
        "codex:${session.key.processGeneration}:$encoded",
        session.key.repositoryIdentity,
        checkoutRoot,
        session.key.runId,
        session.key.processGeneration,
        "codex",
        id,
        threadId,
        title = "Unsupported Codex interaction: $method",
        reason = explanation,
        subject = params.toString(),
        inputText = message.toString(),
        options = emptyList(),
        createdAt = "",
        deadlineAt = "",
        state = ApprovalState.PENDING,
        providerPayload = message.toString(),
      )
    return request
  }

  private fun admit(
    request: ApprovalRequestRecord,
    body: JsonObject,
  ): Boolean =
    synchronized(lock) {
      val id = request.providerRequestId
      val previous = entries[id]
      when {
        previous != null -> false
        entries.size >= MAX_ENTRIES -> {
          stop("Codex exceeded unsupported request capacity.")
          false
        }
        else -> {
          entries[id] = UnsupportedCodexRequest(request, body)
          true
        }
      }
    }

  private fun register(
    session: ApprovalOwnerSession,
    request: ApprovalRequestRecord,
  ) {
    val registered =
      catchingNonCancellation {
        session.register(
          ApprovalRegistrationCommand(
            request,
            null,
            ApprovalActionClass.PROVIDER_INTERACTION,
            false,
            ApprovalProviderDisposition.INTERACTION_REQUIRED,
            null,
          ),
        )
      }.getOrElse { return stop("Codex unsupported request could not be recorded: ${it.message}") }
    when (registered) {
      is ApprovalRegistrationResult.Registered -> onCard(registered.request)
      is ApprovalRegistrationResult.Refused -> onCard(registered.request)
      is ApprovalRegistrationResult.AutoAllowed -> onCard(registered.request)
      ApprovalRegistrationResult.IdentityConflict -> return
    }
  }

  fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState? =
    synchronized(lock) {
      val entry = entries[settled.providerRequestId] ?: return null
      if (entry.cleared) return ApprovalDeliveryState.NOT_REQUIRED
      if (!sameApprovalRegistrationPayload(
          entry.request,
          settled,
        )
      ) {
        return ApprovalDeliveryState.UNCERTAIN
      }
      entry.delivery?.let {
        return if (it == ApprovalDeliveryState.WRITTEN) ApprovalDeliveryState.NOT_REQUIRED else it
      }
      if (settled.state == ApprovalState.PENDING) return ApprovalDeliveryState.UNCERTAIN
      val body = entry.body
      val frame =
        buildJsonObject {
          put(K.ID, settled.providerRequestId.unsupportedJson())
          body.forEach { (key, value) -> put(key, value) }
        }
      val delivered = if (write(frame)) ApprovalDeliveryState.WRITTEN else ApprovalDeliveryState.UNCERTAIN
      entry.delivery = delivered
      delivered
    }

  fun clear() {
    val requests = synchronized(lock) { entries.values.onEach { it.cleared = true }.map { it.request } }
    requests.forEach {
      owner()?.clearByProvider(
        it.approvalId,
        it.providerRequestId,
        ApprovalProviderClearance.RESOLVED,
      )
    }
  }

  private fun matches(
    provided: String?,
    actual: String,
  ): Boolean = provided == null || provided == actual

  private fun response(
    method: String,
    params: JsonObject,
    allow: Boolean,
  ): JsonObject {
    val result =
      when (method) {
        K.ITEM_COMMAND_APPROVAL, K.ITEM_FILE_APPROVAL -> {
          val available = params[K.AVAILABLE_DECISIONS]
          val decisions =
            if (available == null || available == JsonNull) {
              CODEX_DEFAULT_APPROVAL_DECISIONS
            } else {
              (available as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.content }
            }
          val preferred = if (allow) listOf(K.ACCEPT, K.ACCEPT_FOR_SESSION) else listOf(K.CANCEL, K.DECLINE)
          val decision = preferred.firstOrNull { it in decisions } ?: return unsupportedResponse(method)
          buildJsonObject { put(K.PROVIDER_DECISION, decision) }
        }
        K.PERMISSIONS_APPROVAL ->
          buildJsonObject {
            put(K.PERMISSIONS, if (allow) params[K.PERMISSIONS] ?: buildJsonObject {} else buildJsonObject {})
            put(K.SCOPE, K.TURN)
          }
        K.MCP_ELICITATION -> buildJsonObject { put(K.ACTION, K.CANCEL) }
        K.TOOL_REQUEST_USER_INPUT -> buildJsonObject { put(K.ANSWERS, buildJsonObject {}) }
        else -> return unsupportedResponse(method)
      }
    return buildJsonObject { put(K.RESULT, result) }
  }

  private fun unsupportedResponse(method: String): JsonObject =
    buildJsonObject {
      put(
        K.ERROR,
        buildJsonObject {
          put(K.CODE, METHOD_NOT_FOUND)
          put(K.MESSAGE, "Unsupported Codex request: $method")
        },
      )
    }

  private companion object {
    const val MAX_ENTRIES = 1024
    const val METHOD_NOT_FOUND = -32601
  }
}

private class UnsupportedCodexRequest(val request: ApprovalRequestRecord, val body: JsonObject) {
  var delivery: ApprovalDeliveryState? = null
  var cleared = false
}

private val INTEGER_ID = Regex("0|-?[1-9][0-9]*")

private fun JsonElement?.unsupportedId(): ApprovalProviderRequestId? {
  val id = this as? JsonPrimitive ?: return null
  return when {
    id.isString -> ApprovalProviderRequestId.Text(id.content)
    INTEGER_ID.matches(id.content) -> ApprovalProviderRequestId.Integer(id.content)
    else -> null
  }
}

private fun ApprovalProviderRequestId.unsupportedJson(): JsonElement =
  when (this) {
    is ApprovalProviderRequestId.Text -> JsonPrimitive(value)
    is ApprovalProviderRequestId.Integer -> Json.parseToJsonElement(value)
  }
