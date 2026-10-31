package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class ExecutionSnapshotTest {
  @Test
  fun `the diff reports changed, added and removed keys and never the per-read timestamps`() {
    val item = workItem("SKILL-1")
    val later = Instant.fromEpochMilliseconds(60_000)
    val previous = snapshot(item.copy(activeDurationMs = 1), emptyDetail.copy(currentPhase = "plan", workflowId = "wf-1"))
    val current = snapshot(
      item.copy(activeDurationMs = 2, activeDurationAsOf = later),
      emptyDetail.copy(currentPhase = "implement", invocationId = "inv-1", observedAt = later, updatedAt = later),
    )

    val diff = diff(previous, current)

    assertEquals(listOf(ChangedEntry("detail.currentPhase", "plan", "implement")), diff.changed)
    assertEquals(listOf(SnapshotEntry("detail.invocationId", "inv-1")), diff.added)
    assertEquals(listOf("detail.workflowId"), diff.removed)
    listOf(previous.render(), current.render(), diff.render()).forEach { text ->
      listOf("observedAt", "updatedAt", "activeDuration", later.toString()).forEach { assertFalse(it in text, "$it in $text") }
    }
  }
}
