package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.approval.model.ApprovalProviderDisposition
import skillbill.contracts.CanonicalJson
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import skillbill.ports.approval.model.ApprovalProviderClearance
import skillbill.ports.approval.model.ApprovalRegistrationCommand
import skillbill.ports.approval.model.ApprovalRegistrationResult
import skillbill.infrastructure.launcher.process.cursor.CursorAcpKeys as K

internal class CursorAcpPermissions(
  private val owner: ApprovalOwnerSession,
  private val cwd: String,
  private val write: (JsonObject) -> Boolean,
  private val onCard: (ApprovalRequestRecord) -> Unit,
  private val stop: (String) -> Unit,
  private val record: (String) -> Unit,
  private val provider: AcpProvider = AcpProvider.Cursor,
) {
  private val lock = Any()
  private var session: String? = null
  private var released = false
  private val tools = LinkedHashMap<String, JsonObject>()
  private val pending = LinkedHashMap<ApprovalProviderRequestId, CursorPendingPermission>()
  private val consumed = LinkedHashMap<ApprovalProviderRequestId, String>()
  private val cleared = mutableSetOf<ApprovalProviderRequestId>()

  fun bindSession(id: String) {
    session = id
  }

  fun observeTool(update: JsonObject) {
    val id = update.text(K.TOOL_CALL_ID) ?: return
    val complete = update.text(K.STATUS) in setOf("completed", "failed")
    synchronized(lock) {
      if (tools.size >= LIMIT && id !in tools) return stop("${provider.name} exceeded tool retention capacity.")
      tools[id] = merge(tools[id], update)
    }
    if (complete) clear { it.toolId == id }
  }

  fun request(message: JsonObject) {
    val decoded = CursorPermissionRequests.decode(message, session)
    if (decoded == null) {
      record("seam=${provider.seam} outcome=invalid_permission_cancelled")
      writeCancelled(message[K.ID])
      return
    }
    if (!admit(decoded.id, CanonicalJson.encode(message))) return
    val merged = synchronized(lock) { merge(tools[decoded.toolId], decoded.tool) }
    val waiting = CursorPermissionRequests.build(owner.key, cwd, decoded, merged, provider)
    synchronized(lock) { pending[decoded.id] = waiting }
    val registration =
      catchingNonCancellation {
        owner.register(
          ApprovalRegistrationCommand(
            waiting.request,
            waiting.canonical,
            waiting.actionClass,
            waiting.writeCapable,
            ApprovalProviderDisposition.NONE,
            null,
          ),
        )
      }.getOrElse { return stop("${provider.name} approval registration failed: ${it.message.orEmpty()}") }
    show(registration)
  }

  private fun writeCancelled(id: JsonElement?) {
    if (id == null) return
    write(
      buildJsonObject {
        put(K.JSONRPC, "2.0")
        put(K.ID, id)
        put(
          K.RESULT,
          buildJsonObject {
            put(
              K.OUTCOME,
              buildJsonObject {
                put(K.OUTCOME, K.CANCELLED)
              },
            )
          },
        )
      },
    )
  }

  private fun admit(
    id: ApprovalProviderRequestId,
    snapshot: String,
  ): Boolean =
    synchronized(lock) {
      val previous = consumed[id]
      when {
        previous != null -> {
          if (previous != snapshot) stop("${provider.name} reused a permission id with changed content.")
          false
        }
        consumed.size >= LIMIT || released -> {
          stop("${provider.name} approval capacity or lifetime ended.")
          false
        }
        else -> {
          consumed[id] = snapshot
          true
        }
      }
    }

  private fun show(registration: ApprovalRegistrationResult) {
    when (registration) {
      is ApprovalRegistrationResult.Registered -> onCard(registration.request)
      is ApprovalRegistrationResult.AutoAllowed -> onCard(registration.request)
      is ApprovalRegistrationResult.Refused -> onCard(registration.request)
      ApprovalRegistrationResult.IdentityConflict ->
        stop("${provider.name} approval identity conflicted with retained content.")
    }
  }

  fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState =
    synchronized(lock) {
      val id = settled.providerRequestId
      val waiting = pending[id]
      when {
        id in cleared -> ApprovalDeliveryState.NOT_REQUIRED
        released || waiting == null -> ApprovalDeliveryState.UNCERTAIN
        waiting.written || !waiting.matches(settled) -> ApprovalDeliveryState.UNCERTAIN
        else -> dispatch(waiting, settled)
      }
    }

  private fun dispatch(
    waiting: CursorPendingPermission,
    settled: ApprovalRequestRecord,
  ): ApprovalDeliveryState {
    val kind =
      when (settled.state) {
        ApprovalState.ALLOWED -> CursorPermissionKind.ALLOW_ONCE.wireValue
        ApprovalState.DENIED -> CursorPermissionKind.REJECT_ONCE.wireValue
        else -> null
      }
    val choice = waiting.options.firstOrNull { it.kind == kind && it.id == settled.selectedOptionId }
    if (settled.state == ApprovalState.ALLOWED && choice == null) return ApprovalDeliveryState.UNCERTAIN
    waiting.written = true
    return if (write(
        buildJsonObject {
          put(K.JSONRPC, "2.0")
          put(K.ID, settled.providerRequestId.cursorJson())
          put(
            K.RESULT,
            buildJsonObject {
              put(
                K.OUTCOME,
                buildJsonObject {
                  put(K.OUTCOME, if (choice == null) K.CANCELLED else K.SELECTED)
                  choice?.let { put(K.OPTION_ID, it.id) }
                },
              )
            },
          )
        },
      )
    ) {
      ApprovalDeliveryState.WRITTEN
    } else {
      ApprovalDeliveryState.UNCERTAIN
    }
  }

  fun pendingWait(): ApprovalPendingWait? = owner.pendingWait()

  fun clear() = clear { true }

  private fun clear(matches: (CursorPendingPermission) -> Boolean) {
    val removed =
      synchronized(lock) {
        pending.values.filter(matches).also { values ->
          values.forEach {
            pending.remove(it.request.providerRequestId)
            cleared += it.request.providerRequestId
          }
        }
      }
    removed.forEach {
      owner.clearByProvider(
        it.request.approvalId,
        it.request.providerRequestId,
        ApprovalProviderClearance.RESOLVED,
      )
    }
  }

  fun release() {
    if (synchronized(lock) { released }) return
    catchingNonCancellation {
      owner.release()
    }.onFailure { record("seam=${provider.seam} outcome=approval_release_failed") }
    synchronized(lock) {
      released = true
      pending.clear()
      tools.clear()
    }
  }

  private companion object {
    const val LIMIT = 1024
  }
}

private fun merge(
  old: JsonObject?,
  update: JsonObject,
): JsonObject =
  JsonObject(
    old.orEmpty() +
      update.filterValues { it != JsonNull },
  )
