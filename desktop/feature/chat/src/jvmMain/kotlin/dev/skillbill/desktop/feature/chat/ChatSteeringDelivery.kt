package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

internal class ChatSteeringDelivery(
  private val repository: ExecutionChatRepository,
  private val diagnostics: Diagnostics,
  private val thread: ChatThread,
  private val scope: CoroutineScope,
  private val ownerLock: Any,
  private val valid: (String) -> Boolean,
  private val drain: () -> Unit,
  private val accepted: () -> Unit = {},
) {
  private class Attempt(val request: ChatSteeringRequest, val entries: List<QueuedChatMessage>) {
    var observer: Job? = null
    var dispatch: Job? = null
  }
  private var active: Attempt? = null
  private var settling = false

  fun send(sessionId: String, agentId: String) = synchronized(ownerLock) {
    if (!valid(sessionId)) return@synchronized
    val claim = thread.claimDelivery(UUID.randomUUID().toString()) ?: return@synchronized
    val request = ChatSteeringRequest(sessionId, agentId, claim.deliveryId,
      claim.entries.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"), claim.entries.flatMap { it.images })
    val attempt = Attempt(request, claim.entries)
    active = attempt
    attempt.observer = scope.launch {
      repository.steeringUpdates(agentId, sessionId).collect { update ->
        if (update.sessionId == sessionId && update.result.deliveryId == claim.deliveryId) apply(attempt, update.result)
        else diagnostics.record("chat.steering_unmatched_update")
      }
    }
    if (active !== attempt) {
      attempt.observer?.cancel()
      return@synchronized
    }
    attempt.dispatch = scope.launch {
      val result = try {
        if (valid(sessionId)) repository.steer(request)
        else ChatSteeringResult.NoActiveTurn(claim.deliveryId, "The chat session changed before delivery")
      } catch (cancelled: CancellationException) {
        apply(attempt, ChatSteeringResult.DeliveryUncertain(claim.deliveryId, "Delivery was interrupted; Claude may have received this follow-up"))
        throw cancelled
      } catch (failure: Exception) {
        diagnostics.record("chat.steering_request_failed")
        ChatSteeringResult.DeliveryUncertain(claim.deliveryId, "Delivery could not be confirmed; this follow-up remains retained")
      }
      apply(attempt, result)
    }
  }

  private fun apply(attempt: Attempt, result: ChatSteeringResult, settlement: Boolean = false) = synchronized(ownerLock) {
    if (settling && !settlement) {
      diagnostics.record("chat.steering_settlement_in_progress")
      return@synchronized
    }
    if (active !== attempt || !valid(attempt.request.sessionId)) {
      diagnostics.record("chat.steering_stale_owner")
      return@synchronized
    }
    when (val transition = thread.reduceDelivery(result)) {
      ChatThread.DeliveryTransition.Accepted -> {
        diagnostics.record("chat.steering_accepted")
        thread.endStreaming()
        thread.append(ChatMessage.Operator(attempt.request.text, attempt.entries.singleOrNull()?.action,
          attempt.request.images, deliveryId = result.deliveryId))
        accepted()
        retire(attempt)
        if (!settling) drain()
      }
      is ChatThread.DeliveryTransition.Restored -> {
        diagnostics.record("chat.steering_restored")
        thread.append(ChatMessage.Notice("Follow-up queued: ${transition.reason}"))
        retire(attempt)
        if (!settling) drain()
      }
      is ChatThread.DeliveryTransition.RetainedUncertain -> {
        diagnostics.record("chat.steering_retained_uncertain")
        thread.append(ChatMessage.Notice(transition.reason))
      }
      ChatThread.DeliveryTransition.Ignored -> diagnostics.record("chat.steering_ignored_update")
    }
  }

  fun settle() = synchronized(ownerLock) {
    val attempt = active ?: return@synchronized
    val previous = settling
    settling = true
    try {
      apply(attempt, repository.settleSteering(attempt.request.agentId, attempt.request.sessionId, attempt.request.deliveryId), settlement = true)
    } finally {
      settling = previous
    }
  }

  fun detach(stop: () -> Unit) = synchronized(ownerLock) {
    val previous = settling
    settling = true
    try {
      settle()
      stop()
      settle()
      active?.let(::retire)
    } finally {
      settling = previous
    }
  }

  fun recover(deliveryId: String, retry: Boolean): Boolean = synchronized(ownerLock) {
    if (thread.delivery.value?.deliveryId != deliveryId) return@synchronized false
    if (retry) settle() else {
      val attempt = active
      if (attempt != null) {
        settling = true
        try {
          val result = repository.settleSteering(attempt.request.agentId, attempt.request.sessionId, deliveryId)
          if (result is ChatSteeringResult.Accepted) apply(attempt, result, settlement = true)
        } finally {
          settling = false
        }
      }
    }
    if (thread.delivery.value?.deliveryId != deliveryId) return@synchronized true
    if (retry && thread.delivery.value?.status != ChatDeliveryStatus.Uncertain) return@synchronized false
    active?.let(::retire)
    val released = thread.releaseDelivery(deliveryId, retry)
    if (released) diagnostics.record(if (retry) "chat.steering_explicit_retry" else "chat.steering_explicit_discard")
    released
  }

  private fun retire(attempt: Attempt) {
    active = null
    repository.retireSteering(attempt.request.agentId, attempt.request.sessionId, attempt.request.deliveryId)
    attempt.observer?.cancel()
    attempt.dispatch?.cancel()
  }
}
