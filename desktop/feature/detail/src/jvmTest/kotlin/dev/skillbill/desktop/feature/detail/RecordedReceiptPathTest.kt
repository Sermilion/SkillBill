package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalWorktree
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordedReceiptPathTest {
  @Test
  fun `absolute recorded paths stay absolute and openable`() {
    val result = resolveRecordedReceiptPath("/tmp/agent/history.md", emptyDetail, workItem("goal"))
    assertEquals(RecordedReceiptPath("/tmp/agent/history.md", openable = true), result)
  }

  @Test
  fun `relative paths resolve against an active detail goal worktree`() {
    val detail = emptyDetail.copy(goalWorktree = worktree("/checkouts/goal", GoalWorktreeState.Active))
    val result = resolveRecordedReceiptPath("agent/history.md", detail, workItem("goal"))
    assertEquals(RecordedReceiptPath("/checkouts/goal/agent/history.md", openable = true), result)
  }

  @Test
  fun `relative paths resolve against a retained detail goal worktree`() {
    val detail = emptyDetail.copy(goalWorktree = worktree("/checkouts/goal", GoalWorktreeState.Retained))
    val result = resolveRecordedReceiptPath("agent/decisions.md", detail, workItem("goal"))
    assertEquals(RecordedReceiptPath("/checkouts/goal/agent/decisions.md", openable = true), result)
  }

  @Test
  fun `removed detail worktree blocks opening even when the header worktree is active`() {
    val detail = emptyDetail.copy(goalWorktree = worktree("/removed", GoalWorktreeState.Removed))
    val item = workItem("goal").copy(goalWorktree = worktree("/active", GoalWorktreeState.Active))
    val result = resolveRecordedReceiptPath("agent/history.md", detail, item)
    assertEquals(RecordedReceiptPath("agent/history.md", openable = false), result)
  }

  @Test
  fun `missing worktree keeps the fact visible without a checkout fallback`() {
    val result = resolveRecordedReceiptPath("agent/decisions.md", emptyDetail, workItem("goal"))
    assertEquals(RecordedReceiptPath("agent/decisions.md", openable = false), result)
  }

  @Test
  fun `a relative worktree base stays visible without opening against the process directory`() {
    val detail = emptyDetail.copy(goalWorktree = worktree("checkouts/goal", GoalWorktreeState.Active))
    val result = resolveRecordedReceiptPath("agent/history.md", detail, workItem("goal"))
    assertEquals(RecordedReceiptPath("agent/history.md", openable = false), result)
  }

  @Test
  fun `a relative path that leaves the worktree stays visible without opening`() {
    val detail = emptyDetail.copy(goalWorktree = worktree("/checkouts/goal", GoalWorktreeState.Active))
    val result = resolveRecordedReceiptPath("../other/secret.md", detail, workItem("goal"))
    assertEquals(RecordedReceiptPath("../other/secret.md", openable = false), result)
  }

  @Test
  fun `an invalid recorded path is omitted from resolution`() {
    assertNull(resolveRecordedReceiptPath("agent/\u0000history.md", emptyDetail, workItem("goal")))
  }

  private fun worktree(path: String, state: GoalWorktreeState) = GoalWorktree(path, state, null, null)
}
