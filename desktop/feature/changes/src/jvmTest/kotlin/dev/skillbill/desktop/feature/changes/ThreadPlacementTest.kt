package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.domain.model.DiffHunk
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiff
import dev.skillbill.desktop.core.domain.model.FileDiffState
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadPlacementTest {
  @Test
  fun `threads occupy a line slot, file slot or general block exactly once`() {
    val files = listOf(file("src/A.kt"), file("src/B.kt"))
    val loaded = listOf(
      LoadedDiff(files[0], FileDiffState.Shown(FileDiff(listOf(DiffHunk("@@", listOf(DiffLine(DiffLineKind.Added, null, 10, "+new")))), 1, 0)), 2, 4),
      LoadedDiff(files[1], FileDiffState.Shown(FileDiff(listOf(DiffHunk("@@", listOf(DiffLine(DiffLineKind.Removed, 5, null, "-old")))), 0, 1)), 2, 4),
    )
    val threads = listOf(
      thread("new", "src/A.kt", CommentSide.New, 10),
      thread("old", "src/B.kt", CommentSide.Old, 5),
      thread("outdated", "src/A.kt", CommentSide.New, 10, outdated = true),
      thread("hidden", "src/A.kt", CommentSide.New, 99),
      thread("outside", "src/C.kt", CommentSide.New, 10),
      thread("general", null, null, null),
    )

    val placement = placeThreads(files, loaded, threads)

    assertEquals(listOf("outside", "general"), placement.general.map { it.id })
    assertEquals(listOf("outdated", "hidden"), placement.files[0].fileSlot.map { it.id })
    assertEquals(listOf("new"), placement.files[0].afterLine.values.flatten().map { it.id })
    assertEquals(listOf("old"), placement.files[1].afterLine.values.flatten().map { it.id })
    assertEquals(threads.map { it.id }.sorted(), (placement.general + placement.files.flatMap { it.fileSlot + it.afterLine.values.flatten() }).map { it.id }.sorted())
  }

  private fun file(path: String) = ChangedFile(path, null, ChangeStatus.Modified, 1, 1)

  private fun thread(id: String, path: String?, side: CommentSide?, line: Int?, outdated: Boolean = false) =
    CommentThread(id, path, side, line, null, CommentState.Open, outdated, null, null, null, emptyList())
}
