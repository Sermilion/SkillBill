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
    val body = acceptance(method, params)
    writeResult(id, body)
    val session = owner() ?: return
    val requestThread = (params[K.THREAD_ID] as? JsonPrimitive)?.content
    val requestTurn = (params[K.TURN_ID] as? JsonPrimitive)?.content
    if (!matches(requestThread, threadId) || !matches(requestTurn, turnId)) return
    val request = request(message, session, id, threadId)
    if (admit(request, body)) register(session, request, id)
  }

  private fun writeResult(
    id: ApprovalProviderRequestId,
    body: JsonObject,
  ) {
    write(
      buildJsonObject {
        put(K.ID, id.unsupportedJson())
        put(K.RESULT, body)
      },
    )
  }

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
    id: ApprovalProviderRequestId,
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
      if (entry.sent ||
        !sameApprovalRegistrationPayload(
          entry.request,
          settled,
        ) || settled.state == ApprovalState.ALLOWED
      ) {
        return ApprovalDeliveryState.UNCERTAIN
      }
      entry.sent = true
      val body = entry.body
      val frame =
        buildJsonObject {
          put(K.ID, settled.providerRequestId.unsupportedJson())
          put(K.RESULT, body)
        }
      if (write(frame)) ApprovalDeliveryState.WRITTEN else ApprovalDeliveryState.UNCERTAIN
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

  private fun acceptance(
    method: String,
    params: JsonObject,
  ): JsonObject =
    when (method) {
      K.ITEM_COMMAND_APPROVAL -> {
        val available = params[K.AVAILABLE_DECISIONS]
        val decisions =
          if (available == null || available == JsonNull) {
            CODEX_DEFAULT_APPROVAL_DECISIONS
          } else {
            (available as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.content }
          }
        val decision = listOf(K.ACCEPT, K.ACCEPT_FOR_SESSION).firstOrNull { it in decisions } ?: K.ACCEPT
        buildJsonObject { put(K.PROVIDER_DECISION, decision) }
      }
      K.PERMISSIONS_APPROVAL ->
        buildJsonObject {
          put(K.PERMISSIONS, params[K.PERMISSIONS] ?: buildJsonObject {})
          put(K.SCOPE, "turn")
        }
      K.MCP_ELICITATION ->
        buildJsonObject {
          put(K.ACTION, K.ACCEPT)
          put(K.CONTENT, JsonNull)
        }
      else -> buildJsonObject { put(K.PROVIDER_DECISION, K.ACCEPT) }
    }

  private companion object {
    const val MAX_ENTRIES = 1024
  }
}

private class UnsupportedCodexRequest(val request: ApprovalRequestRecord, val body: JsonObject) {
  var sent = false
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
