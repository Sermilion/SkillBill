package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.ChangeScopeTarget
import dev.skillbill.desktop.core.domain.model.ChangeScopeTimeline
import dev.skillbill.desktop.core.domain.model.ScopeCommit
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.ScopeSubtaskState
import kotlin.test.Test
import kotlin.test.assertEquals

class ScopeSelectionTest {
  @Test
  fun `picker search matches a whole number or a name in any case`() {
    val subtasks = listOf(9 to "Parser", 19 to "Renderer", 29 to "Loader", 30 to "Public api client").map { (id, name) ->
      ScopeSubtask(id, name, ScopeSubtaskState.DONE, "e", listOf("e"), false, emptyList())
    }

    assertEquals(listOf(9), filterSubtasks(subtasks, "9").map { it.id })
    assertEquals(listOf(30), filterSubtasks(subtasks, " API ").map { it.id })
  }

  @Test
  fun `step target skips squash members already in the selection`() {
    val subtasks = listOf(
      ScopeSubtask(2, "b", ScopeSubtaskState.DONE, "e", listOf("shared"), false, emptyList()),
      ScopeSubtask(3, "c", ScopeSubtaskState.DONE, "e", listOf("shared"), false, emptyList()),
      ScopeSubtask(4, "d", ScopeSubtaskState.DONE, "f", listOf("f"), false, emptyList()),
    )
    assertEquals(2, stepTarget(subtasks, ChangeScope.Subtasks(4, 4), forward = false))
    assertEquals(4, stepTarget(subtasks, ChangeScope.Subtasks(2, 3), forward = true))
  }

  @Test
  fun `step forward from non adjacent squash lands on the next column`() {
    val subtasks = listOf(
      ScopeSubtask(1, "a", ScopeSubtaskState.DONE, "s", listOf("s"), false, emptyList()),
      ScopeSubtask(2, "b", ScopeSubtaskState.DONE, "t", listOf("t"), false, emptyList()),
      ScopeSubtask(3, "c", ScopeSubtaskState.DONE, "s", listOf("s"), false, emptyList()),
    )
    assertEquals(2, stepTarget(subtasks, ChangeScope.Subtasks(1, 1), forward = true))
  }

  @Test
  fun `commit selection steps, filters by subject, and switch back rejects partial ownership`() {
    val commits = listOf(
      ScopeCommit("a", "base", "First change", listOf(1)),
      ScopeCommit("op", "a", "Operator edit", emptyList()),
      ScopeCommit("b", "op", "Second change", listOf(2)),
    )
    val subtasks = listOf(
      ScopeSubtask(1, "Alpha", ScopeSubtaskState.DONE, "a", listOf("a"), false, emptyList()),
      ScopeSubtask(2, "Beta", ScopeSubtaskState.DONE, "b", listOf("b"), false, emptyList()),
    )
    val target = ChangeScopeTarget("goal", "/repo", ChangeBaseline.TargetMergeBase("/repo", "main"), subtasks, true, timeline = ChangeScopeTimeline("base", "b", commits))
    assertEquals("op", commitStepTarget(commits, ChangeScope.Commits(listOf("a")), true))
    assertEquals(listOf("op"), filterCommits(commits, subtasks, "OPERATOR").map { it.sha })
    assertEquals(ChangeScope.Subtasks(1, 1), subtaskSelectionOf(target, ChangeScope.Commits(listOf("a"))))
    assertEquals(ChangeScope.All, subtaskSelectionOf(target, ChangeScope.Commits(listOf("op"))))
  }
}
