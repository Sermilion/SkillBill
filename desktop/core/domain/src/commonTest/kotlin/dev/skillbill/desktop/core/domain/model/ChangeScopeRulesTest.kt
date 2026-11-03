package dev.skillbill.desktop.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangeScopeRulesTest {
  private val timeline = ChangeScopeTimeline(
    "base",
    "head",
    listOf(
      ScopeCommit("op-before", "base", "operator before", emptyList()),
      ScopeCommit("s1", "op-before", "one", listOf(1)),
      ScopeCommit("op-middle", "s1", "operator middle", emptyList()),
      ScopeCommit("s2", "op-middle", "two", listOf(2)),
      ScopeCommit("s3", "s2", "three", listOf(3)),
    ),
  )
  private val subtasks = listOf(
    ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "s1", listOf("s1"), false, listOf("one.txt")),
    ScopeSubtask(2, "two", ScopeSubtaskState.DONE, "s2", listOf("s2"), false, emptyList()),
    ScopeSubtask(3, "three", ScopeSubtaskState.RUNNING, null, listOf("s3"), false, emptyList()),
    ScopeSubtask(4, "four", ScopeSubtaskState.NOT_STARTED, null, emptyList(), false, emptyList()),
  )
  private val target = ChangeScopeTarget(
    "goal", "/worktree", ChangeBaseline.TargetMergeBase("/worktree", "main"), subtasks, true, timeline = timeline,
  )

  @Test
  fun `operator scope includes all operator commits`() {
    assertEquals(
      ScopeRead.PerCommit(listOf(timeline.commits[0], timeline.commits[2])),
      resolveScope(target, ChangeScope.Operator),
    )
  }

  @Test
  fun `removed worktree never reads the repository working tree`() {
    val removed = target.copy(committedOnly = true)
    assertEquals(false, ScopeKind.Uncommitted in availableScopes(removed))
    assertEquals(ScopeRead.Committed("s2", "s3"), resolveScope(removed, ChangeScope.Subtasks(3, 3)))
    val withoutCommit = removed.copy(timeline = timeline.copy(commits = timeline.commits.dropLast(1)))
    assertEquals(ScopeRead.Unavailable("no end commit"), resolveScope(withoutCommit, ChangeScope.Subtasks(3, 3)))
  }

  @Test
  fun `subtask range keeps spanned operator commits and omits operators outside its span`() {
    assertEquals(
      ScopeRead.Committed("op-before", "s2"),
      resolveScope(target, ChangeScope.Subtasks(1, 2)),
    )
    assertEquals(
      ScopeRead.Baseline(ChangeBaseline.SubtaskStart("/worktree", "s2", emptyList())),
      resolveScope(target, ChangeScope.Subtasks(3, 3)),
    )
  }

  @Test
  fun `another subtask in the middle switches the range to selected commits`() {
    val mixed = target.copy(timeline = timeline.copy(commits = listOf(
      ScopeCommit("s1", "base", "one", listOf(1)),
      ScopeCommit("s2", "s1", "four", listOf(4)),
      ScopeCommit("s3", "s2", "three", listOf(3)),
    )))
    assertEquals(
      ScopeRead.PerCommit(listOf(mixed.timeline!!.commits[0], mixed.timeline.commits[2])),
      resolveScope(mixed, ChangeScope.Subtasks(1, 3)),
    )
  }

  @Test
  fun `no-change subtask is not selectable and timeline is required for scopes`() {
    val noChange = target.copy(subtasks = subtasks + ScopeSubtask(5, "none", ScopeSubtaskState.DONE, null, emptyList(), false, emptyList()))
    assertNull(normalizeRange(noChange, 5, 5))
    assertEquals(setOf(ScopeKind.All, ScopeKind.Uncommitted), availableScopes(target.copy(timeline = null)))
    assertEquals(
      setOf(ScopeKind.All, ScopeKind.Uncommitted),
      availableScopes(executionScopeTarget("run", ChangeBaseline.RunStart("/worktree", "start", emptyList()))),
    )
  }

  @Test
  fun `hunk attribution requires exclusive added and removed lines`() {
    val range = FileDiff(
      listOf(hunk(listOf("only three")), hunk(listOf("three", "five")), hunk(listOf("shared"))), 4, 0,
    )
    val three = FileDiff(listOf(hunk(listOf("only three", "three", "shared"))), 3, 0)
    val five = FileDiff(listOf(hunk(listOf("five", "shared"))), 2, 0)
    assertEquals(listOf(3, null, null), attributeHunks(range, mapOf(3 to three, 5 to five)))
  }

  private fun hunk(added: List<String>) = DiffHunk("@@ -1 +1 @@", added.map { DiffLine(DiffLineKind.Added, null, 1, it) })
}
