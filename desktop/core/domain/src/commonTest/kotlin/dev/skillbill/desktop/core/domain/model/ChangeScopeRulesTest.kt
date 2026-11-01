package dev.skillbill.desktop.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangeScopeRulesTest {
  private val subtasks = listOf(
    ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "s1", "e1", listOf("one.txt")),
    ScopeSubtask(2, "two", ScopeSubtaskState.DONE, "s2", "e2", emptyList()),
    ScopeSubtask(3, "three", ScopeSubtaskState.RUNNING, "s3", null, emptyList()),
    ScopeSubtask(4, "four", ScopeSubtaskState.NOT_STARTED, null, null, emptyList()),
  )
  private val target = ChangeScopeTarget(
    "goal", "/worktree", ChangeBaseline.TargetMergeBase("/worktree", "main"), subtasks, true,
  )

  @Test
  fun `resolves committed ranges and a range ending at the running subtask`() {
    assertEquals(
      ScopeRead.Baseline(ChangeBaseline.SubtaskStart("/worktree", "s1", listOf("one.txt"))),
      resolveScope(target, ChangeScope.Subtasks(1, 3)),
    )
    assertEquals(ScopeRead.Committed("s1", "e2"), resolveScope(target, ChangeScope.Subtasks(1, 2)))
    assertEquals(
      ScopeRead.Unavailable("scope not available"),
      resolveScope(target.copy(rangesSupported = false), ChangeScope.Subtasks(1, 2)),
    )
  }

  @Test
  fun `outside ranges leave an earlier unfinished subtask open`() {
    val target = target.copy(
      subtasks = listOf(
        subtasks[0],
        subtasks[1].copy(state = ScopeSubtaskState.INTERRUPTED, endSha = null),
        subtasks[2],
      ),
    )
    assertEquals(
      ScopeRead.Outside(listOf(CommitRange("s1", "e1"), CommitRange("s3", null))),
      resolveScope(target, ChangeScope.Outside),
    )
  }

  @Test
  fun `range selection requires selectable ends but allows gaps`() {
    assertNull(normalizeRange(target, 1, 4))
    val withGap = target.copy(
      subtasks = listOf(
        subtasks[0],
        subtasks[1].copy(state = ScopeSubtaskState.NOT_STARTED, startSha = null, endSha = null),
        subtasks[2],
      ),
    )
    assertEquals(ChangeScope.Subtasks(1, 3), normalizeRange(withGap, 3, 1))
    assertEquals(setOf(ScopeKind.All, ScopeKind.Uncommitted), availableScopes(target.copy(rangesSupported = false)))
    assertEquals(
      setOf(ScopeKind.All, ScopeKind.Uncommitted),
      availableScopes(executionScopeTarget("run", ChangeBaseline.RunStart("/worktree", "start", emptyList()))),
    )
  }

  @Test
  fun `hunk attribution requires exclusive added and removed lines`() {
    val range = FileDiff(
      listOf(
        hunk(added = listOf("only three")),
        hunk(added = listOf("three", "five")),
        hunk(added = listOf("shared")),
      ),
      4,
      0,
    )
    val three = FileDiff(listOf(hunk(added = listOf("only three", "three", "shared"))), 3, 0)
    val five = FileDiff(listOf(hunk(added = listOf("five", "shared"))), 2, 0)
    assertEquals(listOf(3, null, null), attributeHunks(range, mapOf(3 to three, 5 to five)))
  }

  private fun hunk(added: List<String>) = DiffHunk(
    "@@ -1 +1 @@",
    added.map { DiffLine(DiffLineKind.Added, null, 1, it) },
  )
}
