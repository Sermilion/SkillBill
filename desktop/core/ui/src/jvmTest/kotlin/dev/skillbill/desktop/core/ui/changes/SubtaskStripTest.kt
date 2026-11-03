package dev.skillbill.desktop.core.ui.changes

import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtaskStripTest {
  @Test
  fun `bars hide not started subtasks and keep tiny ones visible`() {
    val done = ScopeSubtask(1, "a", ScopeSubtaskState.DONE, "e", listOf("e"), false, emptyList())
    val notStarted = done.copy(id = 2, state = ScopeSubtaskState.NOT_STARTED, recordedSha = null, commitShas = emptyList())

    assertEquals(0f, subtaskBarFraction(notStarted, null, 10_000))
    assertEquals(1f, subtaskBarFraction(done, SubtaskStats(emptyList(), 6_000, 4_000), 10_000))
    assertTrue(subtaskBarFraction(done, SubtaskStats(emptyList(), 1, 0), 10_000) >= 0.06f)
    assertTrue(subtaskBarFraction(done, SubtaskStats(emptyList(), 0, 0), 10_000) >= 0.06f)
    assertTrue(subtaskTooltip(done.copy(changedAfterFinish = true), null).contains("changed after finish"))
    assertTrue(!subtaskTooltip(done, null).contains("changed after finish"))
  }
}
