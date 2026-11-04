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
  fun `commit selections normalize in timeline order and resolve without the working tree`() {
    assertEquals(ChangeScope.Commits(listOf("s1", "op-middle")), normalizeCommits(target, listOf("op-middle", "s1")))
    assertNull(normalizeCommits(target, listOf("gone")))
    assertEquals(ScopeRead.Committed("op-before", "op-middle"), resolveScope(target, ChangeScope.Commits(listOf("op-middle", "s1"))))
    assertEquals(ScopeRead.PerCommit(listOf(timeline.commits[1], timeline.commits[3])), resolveScope(target, ChangeScope.Commits(listOf("s1", "s2"))))
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
    assertEquals(listOf(ScopeHunkOwner.Subtasks(listOf(3)), null, null), attributeHunks(range, mapOf(
      ScopeHunkOwner.Subtasks(listOf(3)) to three,
      ScopeHunkOwner.Subtasks(listOf(5)) to five,
    )))
  }

  @Test
  fun `subtask columns merge shared commits transitively`() {
    val subtasks = listOf(
      ScopeSubtask(1, "a", ScopeSubtaskState.DONE, "s1", listOf("s1"), false, emptyList()),
      ScopeSubtask(2, "b", ScopeSubtaskState.DONE, "s2", listOf("s1", "s3"), false, emptyList()),
      ScopeSubtask(3, "c", ScopeSubtaskState.DONE, "s3", listOf("s3"), false, emptyList()),
      ScopeSubtask(4, "d", ScopeSubtaskState.DONE, "s4", listOf("s4"), false, emptyList()),
    )
    assertEquals(listOf(listOf(1, 2, 3), listOf(4)), subtaskColumns(subtasks).map { it.ids })
  }

  @Test
  fun `non adjacent squash column selects only its members`() {
    val mixed = ChangeScopeTimeline(
      "base",
      "head",
      listOf(
        ScopeCommit("c13", "base", "squash", listOf(1, 3)),
        ScopeCommit("c2", "c13", "two", listOf(2)),
      ),
    )
    val subtasks = listOf(
      ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "c13", listOf("c13"), false, emptyList()),
      ScopeSubtask(2, "two", ScopeSubtaskState.DONE, "c2", listOf("c2"), false, emptyList()),
      ScopeSubtask(3, "three", ScopeSubtaskState.DONE, "c13", listOf("c13"), false, emptyList()),
    )
    val mixedTarget = target.copy(subtasks = subtasks, timeline = mixed)
    assertEquals(ChangeScope.Subtasks(1, 1), normalizeRange(mixedTarget, 3, 3))
    assertEquals(ScopeRead.Committed("base", "c13"), resolveScope(mixedTarget, ChangeScope.Subtasks(1, 1)))
  }

  @Test
  fun `column whose lowest member is not started still normalizes`() {
    val columnTarget = target.copy(
      subtasks = listOf(
        ScopeSubtask(2, "b", ScopeSubtaskState.NOT_STARTED, null, listOf("s"), false, emptyList()),
        ScopeSubtask(3, "c", ScopeSubtaskState.DONE, "s", listOf("s"), false, emptyList()),
      ),
    )
    assertEquals(ChangeScope.Subtasks(2, 3), normalizeRange(columnTarget, 3, 3))
  }

  @Test
  fun `non adjacent squash whose later member is running reads the working tree`() {
    val mixedTarget = target.copy(
      subtasks = listOf(
        ScopeSubtask(1, "one", ScopeSubtaskState.DONE, "c13", listOf("c13"), false, emptyList()),
        ScopeSubtask(2, "two", ScopeSubtaskState.NOT_STARTED, null, emptyList(), false, emptyList()),
        ScopeSubtask(3, "three", ScopeSubtaskState.RUNNING, null, listOf("c13"), false, emptyList()),
      ),
      timeline = ChangeScopeTimeline("base", "head", listOf(ScopeCommit("c13", "base", "squash", listOf(1, 3)))),
    )
    assertEquals(ChangeScope.Subtasks(1, 1), normalizeRange(mixedTarget, 3, 3))
    assertEquals(
      ScopeRead.Baseline(ChangeBaseline.SubtaskStart("/worktree", "base", emptyList())),
      resolveScope(mixedTarget, ChangeScope.Subtasks(1, 1)),
    )
  }

  @Test
  fun `scope hunk groups split operator commits inside a subtask span`() {
    val groups = scopeHunkGroups(target, ChangeScope.Subtasks(1, 2))
    assertEquals(
      listOf(
        ScopeHunkOwner.Subtasks(listOf(1)),
        ScopeHunkOwner.OperatorCommit("op-middle"),
        ScopeHunkOwner.Subtasks(listOf(2)),
      ),
      groups.map { it.owner },
    )
    assertEquals(listOf(false, false, false), groups.map { it.workingTree })
  }

  @Test
  fun `running subtask groups include an operator commit above its last commit`() {
    val runningTarget = target.copy(
      timeline = timeline.copy(commits = timeline.commits + ScopeCommit("op-after", "s3", "operator after", emptyList())),
    )
    val groups = scopeHunkGroups(runningTarget, ChangeScope.Subtasks(3, 3))
    assertEquals(
      listOf(ScopeHunkOwner.Subtasks(listOf(3)), ScopeHunkOwner.OperatorCommit("op-after")),
      groups.map { it.owner },
    )
    assertEquals(true, groups.single { it.owner == ScopeHunkOwner.Subtasks(listOf(3)) }.workingTree)
  }

  private fun hunk(added: List<String>) = DiffHunk("@@ -1 +1 @@", added.map { DiffLine(DiffLineKind.Added, null, 1, it) })
}
