package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import kotlin.test.Test
import kotlin.test.assertEquals
import skillbill.ports.workchanges.model.WorkScopeSubtask
import skillbill.workflow.model.DecompositionStatus

class ChangeScopeMappingTest {
  @Test
  fun `maps runtime state and gives running precedence`() {
    assertEquals(ScopeSubtaskState.DONE, subtask(DecompositionStatus.COMPLETE).toDomain().state)
    assertEquals(ScopeSubtaskState.DONE, subtask(DecompositionStatus.COMPLETED_NO_CHANGE).toDomain().state)
    assertEquals(ScopeSubtaskState.RUNNING, subtask(DecompositionStatus.IN_PROGRESS, running = true).toDomain().state)
    assertEquals(ScopeSubtaskState.INTERRUPTED, subtask(DecompositionStatus.BLOCKED).toDomain().state)
    assertEquals(ScopeSubtaskState.NOT_STARTED, subtask(DecompositionStatus.PENDING).toDomain().state)
    assertEquals(ScopeSubtaskState.NOT_STARTED, subtask(DecompositionStatus.SKIPPED).toDomain().state)
  }

  private fun subtask(status: DecompositionStatus, running: Boolean = false) = WorkScopeSubtask(
    id = 1,
    name = "first",
    status = status,
    running = running,
    startSha = "start",
    endSha = null,
    baselineUntrackedPaths = emptyList(),
  )
}
