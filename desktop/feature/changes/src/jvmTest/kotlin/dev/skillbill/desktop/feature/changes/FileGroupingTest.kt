package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals

class FileGroupingTest {
  @Test
  fun `groups paths by source root and parent directory`() {
    val files = listOf(
      ChangedFile("README.md", null, ChangeStatus.Modified, 1, 0),
      ChangedFile("desktop/agent/decisions.md", null, ChangeStatus.Modified, 1, 0),
      ChangedFile("desktop/feature/x/src/main/A.kt", null, ChangeStatus.Modified, 1, 0),
      ChangedFile("runtime-kotlin/runtime-infra/workflow/src/main/B.kt", null, ChangeStatus.Modified, 1, 0),
    )

    assertEquals(listOf("(root)", "desktop/agent", "desktop/feature/x", "runtime-kotlin/runtime-infra/workflow"), groupFiles(files, "") { 0 }.map { it.module })
  }

  @Test
  fun `the title names the issue key once`() {
    val item = workItem("G").copy(issueKey = "SKILL-455", title = "[SKILL-455] Changes window")

    assertEquals("Changes · SKILL-455 · Changes window", changesWindowTitle(item))
  }

  @Test
  fun `a stream row maps to the file that holds it`() {
    val starts = fileStartIndexes(listOf(null, null, null))

    assertEquals(listOf(0, 2, 4), starts.toList())
    assertEquals(listOf(0, 0, 1, 1, 2, 2, 2), (0..6).map { fileAt(starts, it) })
  }

  @Test
  fun `general and file thread rows are included in file start indexes`() {
    val general = listOf(thread("g1"), thread("g2"))
    val placement = ThreadPlacement(
      general,
      listOf(
        FileThreads(fileSlot = listOf(thread("f1")), afterLine = mapOf(dev.skillbill.desktop.core.ui.diff.DiffLineAddress(0, 0) to listOf(thread("l1")))),
        FileThreads(),
      ),
    )
    val firstFile = ChangedFile("a.kt", null, ChangeStatus.Modified, 1, 0)
    val shown = FileDiff(listOf(DiffHunk("@@", listOf(DiffLine(DiffLineKind.Context, 1, 1, "line")))), 0, 0)
    val loaded = listOf(LoadedDiff(firstFile, FileDiffState.Shown(shown), 2, 4), null)
    val starts = fileStartIndexes(loaded, placement)

    assertEquals(listOf(3, 8), starts.toList())
    assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1), (0..9).map { fileAt(starts, it) })
  }

  private fun thread(id: String) = CommentThread(id, null, null, null, null, CommentState.Open, false, null, null, null, emptyList())
}
