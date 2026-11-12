package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import java.util.UUID

internal class ClaudeSteeringDeliveries(
  private val lock: Any,
  private val active: () -> Boolean,
  private val alive: () -> Boolean,
  private val publish: (ChatSteeringResult) -> Unit,
  private val diagnostics: Diagnostics,
) {
  internal data class Attempt(val id: String, val frame: ChatProcessInput, var result: ChatSteeringResult? = null)
  private val attempts = linkedMapOf<String, Attempt>()

  fun prepare(request: ChatSteeringRequest, encode: (String) -> String): Attempt? = synchronized(lock) {
    if (!active() || attempts.values.any { it.id == request.deliveryId }) return@synchronized null
    val uuid = UUID.randomUUID().toString()
    val frame = ChatProcessInput(encode(uuid)) { input ->
      synchronized(lock) { active() && input.acquireUnconditionally() }
    }
    Attempt(request.deliveryId, frame).also { attempts[uuid] = it }
  }

  fun resolve(attempt: Attempt): ChatSteeringResult = synchronized(lock) {
    attempt.result?.takeIf { it is ChatSteeringResult.Accepted } ?: classify(attempt)
  }

  fun resolveDelivery(deliveryId: String): ChatSteeringResult = synchronized(lock) {
    val attempt = attempts.values.firstOrNull { it.id == deliveryId }
      ?: return@synchronized ChatSteeringResult.NoActiveTurn(deliveryId, "No pending Claude delivery remains")
    resolve(attempt)
  }

  fun acknowledge(uuid: String): Boolean = synchronized(lock) {
    val attempt = attempts[uuid] ?: return@synchronized false
    if (!alive()) {
      diagnostics.record("chat.steering_stale_generation")
    } else if (attempt.result is ChatSteeringResult.Accepted) {
      diagnostics.record("chat.steering_duplicate_acknowledgement")
    } else if (attempt.result is ChatSteeringResult.NoActiveTurn) {
      diagnostics.record("chat.steering_stale_acknowledgement")
    } else {
      attempt.result = ChatSteeringResult.Accepted(attempt.id)
      publish(requireNotNull(attempt.result))
    }
    true
  }

  fun settle() = synchronized(lock) {
    attempts.values.filter { it.result !is ChatSteeringResult.Accepted }.forEach(::classify)
  }

  fun retire(deliveryId: String) = synchronized(lock) {
    attempts.entries.removeAll {
      if (it.value.id != deliveryId) false else {
        it.value.frame.revokeBeforeWrite()
        true
      }
    }
  }

  private fun classify(attempt: Attempt): ChatSteeringResult {
    val result = if (attempt.frame.revokeBeforeWrite()) {
      attempt.frame.written.complete(false)
      ChatSteeringResult.NoActiveTurn(attempt.id, "Claude finished before the follow-up was written")
    } else {
      ChatSteeringResult.DeliveryUncertain(attempt.id, "Claude may have received this follow-up; acknowledgement is pending")
    }
    if (attempt.result != result) {
      attempt.result = result
      publish(result)
    }
    return result
  }
}
