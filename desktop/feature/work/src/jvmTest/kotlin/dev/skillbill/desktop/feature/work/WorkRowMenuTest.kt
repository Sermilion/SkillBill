package dev.skillbill.desktop.feature.work

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkRowMenuTest {
  @Test
  fun `row menu lists launch and finalize from runtime options on terminal standalone`() {
    val item = workItem("rev", kind = WorkKind.Phase, issueKey = null, lifecycle = Lifecycle.Terminal)
    val state = WorkUiState(
      runOptions = mapOf(
        item.id to RunOptions(false, true, "Run again", "claude", listOf("claude"), null, canFinalize = true),
      ),
    )
    val labels = workRowMenuEntries(state, item).map { it.label }
    assertTrue("Run again" in labels)
    assertTrue("Finalize" in labels)
  }

  @Test
  fun `row menu omits finalize when options disallow it`() {
    val review = workItem("rev", kind = WorkKind.Phase, issueKey = null, lifecycle = Lifecycle.Terminal)
    val failed = workItem("fail", kind = WorkKind.Phase, issueKey = null, lifecycle = Lifecycle.Failed)
    val goal = workItem("SKILL-1", kind = WorkKind.Goal, lifecycle = Lifecycle.Terminal)
    val noControls = RunOptions(false, false, "Run again", "claude", listOf("claude"), null)
    fun labels(item: WorkItem) =
      workRowMenuEntries(WorkUiState(runOptions = mapOf(item.id to noControls)), item).map { it.label }
    assertFalse(labels(review).contains("Finalize"))
    assertFalse(labels(failed).contains("Finalize"))
    assertFalse(labels(goal).contains("Finalize"))
  }
}
