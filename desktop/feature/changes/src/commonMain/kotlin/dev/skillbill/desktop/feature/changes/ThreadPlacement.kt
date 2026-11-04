package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import dev.skillbill.desktop.core.domain.model.FileDiffState
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.ui.diff.DiffLineAddress

internal data class ThreadPlacement(val general: List<CommentThread>, val files: List<FileThreads>)
internal data class FileThreads(
  val fileSlot: List<CommentThread> = emptyList(),
  val afterLine: Map<DiffLineAddress, List<CommentThread>> = emptyMap(),
)

internal fun placeThreads(files: List<ChangedFile>, loaded: List<LoadedDiff?>, threads: List<CommentThread>): ThreadPlacement {
  val general = mutableListOf<CommentThread>()
  val slots = files.map { mutableListOf<CommentThread>() }
  val lineSlots = files.map { mutableMapOf<DiffLineAddress, MutableList<CommentThread>>() }
  val indexByPath = files.mapIndexed { index, file -> file.path to index }.toMap()
  threads.forEach { thread ->
    val index = thread.path?.let(indexByPath::get)
    if (index == null) {
      general += thread
      return@forEach
    }
    val diff = (loaded.getOrNull(index)?.state as? FileDiffState.Shown)?.diff?.takeIf { it.hunks.isNotEmpty() }
    if (thread.outdated || thread.line == null || diff == null) {
      slots[index] += thread
      return@forEach
    }
    val address = diff.hunks.asSequence().flatMapIndexed { hunkIndex, hunk ->
      hunk.lines.asSequence().mapIndexed { lineIndex, line ->
        DiffLineAddress(hunkIndex, lineIndex) to line
      }
    }.firstOrNull { (_, line) ->
      when (thread.side) {
        CommentSide.New -> line.newNumber == thread.line && (line.kind == DiffLineKind.Context || line.kind == DiffLineKind.Added)
        CommentSide.Old -> line.oldNumber == thread.line && (line.kind == DiffLineKind.Context || line.kind == DiffLineKind.Removed)
        null -> false
      }
    }?.first
    if (address == null) slots[index] += thread else lineSlots[index].getOrPut(address) { mutableListOf() } += thread
  }
  return ThreadPlacement(general, slots.indices.map { index ->
    FileThreads(slots[index], lineSlots[index].mapValues { it.value.toList() })
  })
}
