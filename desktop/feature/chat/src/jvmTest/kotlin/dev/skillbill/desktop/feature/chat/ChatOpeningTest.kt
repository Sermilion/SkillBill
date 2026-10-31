package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatOpeningTest {
  @Test
  fun `a block outranks a stall, and a stall outranks running`() {
    val blocked = workItem("SKILL-1", lifecycle = Lifecycle.Blocked).copy(running = false, stalledSince = epoch)
    val active = workItem("SKILL-2", lifecycle = Lifecycle.Active).copy(stalledSince = epoch)

    assertEquals(ExecutionState.Blocked, classify(blocked))
    assertEquals(ExecutionState.Stalled, classify(active))
  }
}
