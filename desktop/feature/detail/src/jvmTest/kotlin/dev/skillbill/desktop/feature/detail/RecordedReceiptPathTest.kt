package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalWorktree
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.testing.workItem
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordedReceiptPathTest {
  @Test
  fun `absolute recorded paths stay absolute and openable`() {
    val path = Paths.get(System.getProperty("java.io.tmpdir"), "agent", "history.md").toAbsolutePath().normalize()
    val result = resolveRecordedReceiptPath(path.toString(), emptyDetail, workItem("goal"))
    assertEquals(RecordedReceiptPath(path.toString(), openable = true), result)
  }

  @Test
  fun `relative paths resolve against an active detail goal worktree`() {
    val base = Paths.get(System.getProperty("java.io.tmpdir"), "checkouts", "goal").toAbsolutePath().normalize()
    val detail = emptyDetail.copy(goalWorktree = worktree(base.toString(), GoalWorktreeState.Active))
    val result = resolveRecordedReceiptPath(Paths.get("agent", "history.md").toString(), detail, workItem("goal"))
    assertEquals(RecordedReceiptPath(base.resolve("agent").resolve("history.md").toString(), openable = true), result)
  }

  @Test
  fun `relative paths resolve against a retained detail goal worktree`() {
    val base = Paths.get(System.getProperty("java.io.tmpdir"), "checkouts", "goal").toAbsolutePath().normalize()
    val detail = emptyDetail.copy(goalWorktree = worktree(base.toString(), GoalWorktreeState.Retained))
    val result = resolveRecordedReceiptPath(Paths.get("agent", "decisions.md").toString(), detail, workItem("goal"))
    assertEquals(RecordedReceiptPath(base.resolve("agent").resolve("decisions.md").toString(), openable = true), result)
  }

  @Test
  fun `removed detail worktree blocks opening even when the header worktree is active`() {
    val detail = emptyDetail.copy(goalWorktree = worktree(absolutePath("removed"), GoalWorktreeState.Removed))
    val item = workItem("goal").copy(goalWorktree = worktree(absolutePath("active"), GoalWorktreeState.Active))
    val relative = Paths.get("agent", "history.md").toString()
    val result = resolveRecordedReceiptPath(relative, detail, item)
    assertEquals(RecordedReceiptPath(relative, openable = false), result)
  }

  @Test
  fun `missing worktree keeps the fact visible without a checkout fallback`() {
    val relative = Paths.get("agent", "decisions.md").toString()
    val result = resolveRecordedReceiptPath(relative, emptyDetail, workItem("goal"))
    assertEquals(RecordedReceiptPath(relative, openable = false), result)
  }

  @Test
  fun `a relative worktree base stays visible without opening against the process directory`() {
    val base = Paths.get("checkouts", "goal").toString()
    val relative = Paths.get("agent", "history.md").toString()
    val detail = emptyDetail.copy(goalWorktree = worktree(base, GoalWorktreeState.Active))
    val result = resolveRecordedReceiptPath(relative, detail, workItem("goal"))
    assertEquals(RecordedReceiptPath(relative, openable = false), result)
  }

  @Test
  fun `a relative path that leaves the worktree stays visible without opening`() {
    val base = Paths.get(System.getProperty("java.io.tmpdir"), "checkouts", "goal").toAbsolutePath().normalize()
    val relative = Paths.get("..", "other", "secret.md").toString()
    val detail = emptyDetail.copy(goalWorktree = worktree(base.toString(), GoalWorktreeState.Active))
    val result = resolveRecordedReceiptPath(relative, detail, workItem("goal"))
    assertEquals(RecordedReceiptPath(relative, openable = false), result)
  }

  @Test
  fun `an invalid recorded path is omitted from resolution`() {
    assertNull(resolveRecordedReceiptPath("agent${java.io.File.separator}\u0000history.md", emptyDetail, workItem("goal")))
  }

  private fun absolutePath(name: String) = Paths.get(System.getProperty("java.io.tmpdir"), name).toAbsolutePath().normalize().toString()

  private fun worktree(path: String, state: GoalWorktreeState) = GoalWorktree(path, state, null, null)
}
