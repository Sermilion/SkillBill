package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
import dev.skillbill.desktop.core.testing.FakeExecutionChatRepository
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSteeringDeliveryTest {
  @Test
  fun `teardown consumes final acceptance before retiring tracking and never drains while stopping resources`() = runTest {
    val repository = FakeExecutionChatRepository()
    repository.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "unknown") }
    val thread = ChatThread(Clock.System)
    thread.enqueue(QueuedChatMessage("accepted at close", null, false))
    var drains = 0
    val delivery = ChatSteeringDelivery(repository, RecordingDiagnostics(), thread, backgroundScope, Any(), { true }, { drains++ })
    delivery.send("session", "claude")
    val id = thread.delivery.value!!.deliveryId
    delivery.detach {
      assertTrue(repository.retiredSteering.isEmpty())
      repository.retainedSteering.value = mapOf(("session" to id) to ChatSteeringUpdate("session", ChatSteeringResult.Accepted(id)))
    }
    runCurrent()
    assertEquals(listOf("accepted at close"), thread.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
    assertFalse(thread.hasDeliveryClaim())
    assertEquals(1, repository.retiredSteering.size)
    assertEquals(0, drains)
  }

  @Test
  fun `authoritative rejection restores an uncertain batch and drains once`() = runTest {
    val repository = FakeExecutionChatRepository()
    repository.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "unknown") }
    val thread = ChatThread(Clock.System)
    var rejected = 0
    val image = dev.skillbill.desktop.core.domain.model.ChatImage(byteArrayOf(9), "image/png")
    val original = QueuedChatMessage("first", null, true, images = listOf(image), onRejected = { rejected++ })
    val later = QueuedChatMessage("later", null, false)
    thread.enqueue(original)
    var drains = 0
    val delivery = ChatSteeringDelivery(repository, RecordingDiagnostics(), thread, backgroundScope, Any(), { true }, { drains++ })
    delivery.send("session", "codex")
    runCurrent()
    val id = thread.delivery.value!!.deliveryId
    thread.enqueue(later)

    repository.retainedSteering.value = mapOf(("session" to id) to
      ChatSteeringUpdate("session", ChatSteeringResult.Rejected(id, "turn already ended")))
    runCurrent()

    assertEquals(listOf(original, later), thread.queued.value)
    assertTrue(thread.queued.value.first().images.single() === image)
    assertEquals("Follow-up queued: turn already ended", thread.transcript.value.filterIsInstance<ChatMessage.Notice>().single().text)
    assertFalse(thread.hasDeliveryClaim())
    assertEquals(null, thread.delivery.value)
    assertEquals(1, drains)
    assertEquals(1, repository.retiredSteering.count { it.third == id })
    assertEquals(0, rejected)
  }

  @Test
  fun `teardown waits for cancellation cleanup before final settlement and retirement`() = runTest {
    val repository = FakeExecutionChatRepository()
    repository.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "unknown") }
    val cleanupStarted = CompletableDeferred<Unit>()
    val cleanupFinished = CompletableDeferred<Unit>()
    repository.awaitEndAction = {
      cleanupStarted.complete(Unit)
      cleanupFinished.await()
    }
    val thread = ChatThread(Clock.System)
    val image = dev.skillbill.desktop.core.domain.model.ChatImage(byteArrayOf(4), "image/png")
    val original = QueuedChatMessage("batch", null, false, images = listOf(image))
    thread.enqueue(original)
    val delivery = ChatSteeringDelivery(repository, RecordingDiagnostics(), thread, backgroundScope, Any(), { true }, {})
    delivery.send("session", "codex")
    runCurrent()
    val id = thread.delivery.value!!.deliveryId
    val teardown = async { delivery.detach { repository.end("session") } }
    cleanupStarted.await()

    repository.retainedSteering.value = mapOf(("session" to id) to
      ChatSteeringUpdate("session", ChatSteeringResult.Rejected(id, "turn already ended")))
    runCurrent()
    assertTrue(thread.hasDeliveryClaim())
    assertTrue(repository.retiredSteering.none { it.third == id })

    cleanupFinished.complete(Unit)
    teardown.await()
    assertEquals(listOf(original), thread.queued.value)
    assertTrue(thread.queued.value.single().images.single() === image)
    assertFalse(thread.hasDeliveryClaim())
    assertEquals(1, repository.retiredSteering.count { it.third == id })
  }

  @Test
  fun `settlement restores an authoritative rejection without draining during teardown`() = runTest {
    val repository = FakeExecutionChatRepository()
    repository.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "unknown") }
    repository.settledSteering = { _, id -> ChatSteeringResult.Rejected(id, "turn already ended") }
    val thread = ChatThread(Clock.System)
    thread.enqueue(QueuedChatMessage("first", null, false))
    var drains = 0
    val delivery = ChatSteeringDelivery(repository, RecordingDiagnostics(), thread, backgroundScope, Any(), { true }, { drains++ })
    delivery.send("session", "codex")
    runCurrent()
    val id = thread.delivery.value!!.deliveryId
    thread.enqueue(QueuedChatMessage("later", null, false))

    delivery.detach {}

    assertEquals(listOf("first", "later"), thread.queued.value.map { it.text })
    assertFalse(thread.hasDeliveryClaim())
    assertEquals(null, thread.delivery.value)
    assertEquals(0, drains)
    assertEquals(1, repository.retiredSteering.count { it.third == id })
  }

  @Test
  fun `acknowledgement racing explicit retry releases later entries without replaying the accepted batch`() = runTest {
    val repository = FakeExecutionChatRepository()
    repository.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "unknown") }
    val thread = ChatThread(Clock.System)
    thread.enqueue(QueuedChatMessage("accepted", null, false))
    val delivery = ChatSteeringDelivery(repository, RecordingDiagnostics(), thread, backgroundScope, Any(), { true }, {})
    delivery.send("session", "claude")
    val id = thread.delivery.value!!.deliveryId
    thread.enqueue(QueuedChatMessage("later", null, false))
    repository.retainedSteering.value = mapOf(("session" to id) to ChatSteeringUpdate("session", ChatSteeringResult.Accepted(id)))
    assertTrue(delivery.recover(id, retry = true))
    runCurrent()
    assertEquals(listOf("later"), thread.takeQueuedEntries().map { it.text })
    assertEquals(listOf("accepted"), thread.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
    assertEquals(1, repository.retiredSteering.count { it.third == id })
  }

  @Test
  fun `discarding a pending unacquired delivery never restores it for a later automatic send`() = runTest {
    val repository = FakeExecutionChatRepository()
    val immediate = CompletableDeferred<ChatSteeringResult>()
    repository.steeringResult = { immediate.await() }
    repository.settledSteering = { _, id -> ChatSteeringResult.NoActiveTurn(id, "revoked") }
    val thread = ChatThread(Clock.System)
    thread.enqueue(QueuedChatMessage("discard me", null, false))
    val delivery = ChatSteeringDelivery(repository, RecordingDiagnostics(), thread, backgroundScope, Any(), { true }, {})
    delivery.send("session", "claude")
    val id = thread.delivery.value!!.deliveryId
    thread.enqueue(QueuedChatMessage("later", null, false))
    assertTrue(delivery.recover(id, retry = false))
    assertEquals(listOf("later"), thread.takeQueuedEntries().map { it.text })
    immediate.complete(ChatSteeringResult.Accepted(id))
    runCurrent()
    assertTrue(thread.transcript.value.none { it is ChatMessage.Operator })
    assertEquals(1, repository.retiredSteering.count { it.third == id })
  }

  @Test
  fun `retirement fences an old acknowledgement from a new claim and preserves original entry order`() = runTest {
    val repository = FakeExecutionChatRepository()
    repository.steeringResult = { ChatSteeringResult.DeliveryUncertain(it.deliveryId, "unknown") }
    val thread = ChatThread(Clock.System)
    var rejected = 0
    val original = QueuedChatMessage("first", null, true, onRejected = { rejected++ })
    val later = QueuedChatMessage("later", null, false)
    thread.enqueue(original)
    val delivery = ChatSteeringDelivery(repository, RecordingDiagnostics(), thread, backgroundScope, Any(), { true }, {})
    delivery.send("session", "claude")
    runCurrent()
    val old = thread.delivery.value!!.deliveryId
    thread.enqueue(later)
    assertTrue(delivery.recover(old, retry = true))
    assertEquals(listOf(original, later), thread.queued.value)
    assertEquals(0, rejected)
    delivery.send("session", "claude")
    runCurrent()
    val current = thread.delivery.value!!.deliveryId
    assertFalse(old == current)
    repository.retainedSteering.value = mapOf(("session" to old) to ChatSteeringUpdate("session", ChatSteeringResult.Accepted(old)))
    runCurrent()
    assertEquals(current, thread.delivery.value!!.deliveryId)
    assertTrue(thread.transcript.value.none { it is ChatMessage.Operator })
    repository.retainedSteering.value = mapOf(("session" to current) to ChatSteeringUpdate("session", ChatSteeringResult.Accepted(current)))
    runCurrent()
    assertEquals(listOf("first\n\nlater"), thread.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
    assertEquals(current, thread.transcript.value.filterIsInstance<ChatMessage.Operator>().single().deliveryId)
    assertEquals(0, rejected)
    assertEquals(2, repository.retiredSteering.size)
  }

  @Test
  fun `early acknowledgement wins over a delayed immediate failure without restoring or duplicating the batch`() = runTest {
    val repository = FakeExecutionChatRepository()
    val diagnostics = RecordingDiagnostics()
    val thread = ChatThread(Clock.System)
    val immediate = CompletableDeferred<ChatSteeringResult>()
    var drains = 0
    repository.steeringResult = { request ->
      repository.retainedSteering.value = mapOf((request.sessionId to request.deliveryId) to
        ChatSteeringUpdate(request.sessionId, ChatSteeringResult.Accepted(request.deliveryId)))
      immediate.await()
    }
    thread.enqueue(QueuedChatMessage("first", null, false))
    thread.enqueue(QueuedChatMessage("second", null, false))
    val delivery = ChatSteeringDelivery(repository, diagnostics, thread, backgroundScope, Any(), { true }, { drains++ })
    delivery.send("session", "claude")
    delivery.send("session", "claude")
    runCurrent()
    assertEquals(1, repository.steeringRequests.size)
    assertFalse(thread.hasDeliveryClaim())
    assertEquals(listOf("first\n\nsecond"), thread.transcript.value.filterIsInstance<ChatMessage.Operator>().map { it.text })
    assertEquals(1, drains)
    val id = repository.steeringRequests.single().deliveryId
    immediate.complete(ChatSteeringResult.Rejected(id, "delayed result"))
    runCurrent()
    assertTrue(thread.queued.value.isEmpty())
    assertEquals(1, drains)
    assertEquals(1, repository.retiredSteering.size)
    assertTrue("chat.steering_accepted" in diagnostics.categories)
    assertFalse("chat.steering_restored" in diagnostics.categories)
  }
}
