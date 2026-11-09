package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeSteeringDeliveriesTest {
  @Test
  fun `explicit teardown settlement revokes pending writes and retains acquired uncertainty until retirement`() = runTest {
    val ledger = ClaudeSteeringDeliveries(Any(), { true }, { true }, {}, RecordingDiagnostics())
    val pending = ledger.prepare(request("pending")) { it }!!
    assertIs<ChatSteeringResult.NoActiveTurn>(ledger.resolveDelivery("pending"))
    assertFalse(pending.frame.acquireWrite())
    val acquired = ledger.prepare(request("acquired")) { it }!!
    assertTrue(acquired.frame.acquireWrite())
    assertIs<ChatSteeringResult.DeliveryUncertain>(ledger.resolveDelivery("acquired"))
    ledger.retire("acquired")
    assertFalse(ledger.acknowledge(acquired.frame.line))
  }

  @Test
  fun `completion revokes an unacquired frame but quarantines an acquired write until late replay`() = runTest {
    var active = true
    var alive = true
    val updates = mutableListOf<ChatSteeringResult>()
    val ledger = ClaudeSteeringDeliveries(Any(), { active }, { alive }, updates::add, RecordingDiagnostics())
    val unsent = ledger.prepare(request("unsent")) { it }!!
    active = false
    ledger.settle()
    assertIs<ChatSteeringResult.NoActiveTurn>(ledger.resolve(unsent))
    assertFalse(unsent.frame.acquireWrite())

    active = true
    val acquired = ledger.prepare(request("acquired")) { it }!!
    assertTrue(acquired.frame.acquireWrite())
    acquired.frame.written.complete(false)
    assertIs<ChatSteeringResult.DeliveryUncertain>(ledger.resolve(acquired))
    active = false
    ledger.settle()
    assertTrue(ledger.acknowledge(acquired.frame.line))
    assertEquals(ChatSteeringResult.Accepted("acquired"), ledger.resolve(acquired))
    ledger.retire("acquired")
    assertEquals(ChatSteeringResult.Accepted("acquired"), ledger.resolve(acquired))

    active = true
    val dead = ledger.prepare(request("dead")) { it }!!
    assertTrue(dead.frame.acquireWrite())
    alive = false
    active = false
    ledger.settle()
    ledger.acknowledge(dead.frame.line)
    assertIs<ChatSteeringResult.DeliveryUncertain>(ledger.resolve(dead))
    assertEquals(1, updates.filterIsInstance<ChatSteeringResult.Accepted>().size)
  }

  @Test
  fun `successful write alone is uncertain and early replay survives retirement before return`() = runTest {
    val updates = mutableListOf<ChatSteeringResult>()
    val ledger = ClaudeSteeringDeliveries(Any(), { true }, { true }, updates::add, RecordingDiagnostics())
    val first = ledger.prepare(request("first")) { it }!!
    assertTrue(first.frame.acquireWrite())
    first.frame.written.complete(true)
    assertIs<ChatSteeringResult.DeliveryUncertain>(ledger.resolve(first))
    assertTrue(ledger.acknowledge(first.frame.line))
    assertTrue(ledger.acknowledge(first.frame.line))
    ledger.retire("first")
    assertEquals(ChatSteeringResult.Accepted("first"), ledger.resolve(first))
    assertEquals(1, updates.filterIsInstance<ChatSteeringResult.Accepted>().size)
  }

  private fun request(id: String) = ChatSteeringRequest("session", "claude", id, "follow up")
}
