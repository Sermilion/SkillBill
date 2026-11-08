package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ScopeSubtask
import dev.skillbill.desktop.core.domain.model.SubtaskStats
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.core.domain.model.selectedSubtaskIds
import dev.skillbill.desktop.core.domain.model.touchCount

data class FileGroupUi(val module: String, val insertions: Int, val deletions: Int, val rows: List<FileRowUi>)
data class FileRowUi(val file: ChangedFile, val touchCount: Int)

internal fun moduleOf(path: String): String {
  val segments = path.split('/')
  val sourceIndex = segments.indexOf("src")
  return when {
    sourceIndex > 0 -> segments.take(sourceIndex).joinToString("/")
    segments.size > 1 -> segments.dropLast(1).joinToString("/")
    else -> "(root)"
  }
}

internal fun groupFiles(files: List<ChangedFile>, filter: String, touchCount: (String) -> Int): List<FileGroupUi> =
  files.filter { it.path.contains(filter, ignoreCase = true) }
    .groupBy { moduleOf(it.path) }.map { (module, rows) ->
      FileGroupUi(module, rows.sumOf { it.insertions ?: 0 }, rows.sumOf { it.deletions ?: 0 }, rows.map { FileRowUi(it, touchCount(it.path)) })
    }

internal fun scopeTouchCount(path: String, scope: ChangeScope, stats: Map<Int, SubtaskStats>, subtasks: List<ScopeSubtask>): Int =
  touchCount(path, scope, stats, subtasks) ?: 0

internal fun scopeLabel(scope: ChangeScope, subtasks: List<ScopeSubtask>): String = when (scope) {
  ChangeScope.All -> "All changes"
  ChangeScope.Operator -> "Operator commits"
  ChangeScope.Uncommitted -> "Uncommitted"
  is ChangeScope.Commits -> scope.shas.singleOrNull()?.let { sha -> "Commit ${sha.take(7)}" } ?: "${scope.shas.size} commits"
  is ChangeScope.Subtasks -> {
    val selected = selectedSubtaskIds(subtasks, scope).filter { id -> subtasks.any { it.id == id } }
    when {
      selected.size == 1 -> subtasks.firstOrNull { it.id == selected.single() }?.let { "${it.id} · ${it.name}" } ?: "Subtask ${selected.single()}"
      selected.size > 1 && selected.max() - selected.min() + 1 == selected.size ->
        "Subtasks ${selected.min()}–${selected.max()}"
      selected.size > 1 -> "Subtasks ${selected.sorted().joinToString(" + ")}"
      else -> subtasks.firstOrNull { it.id == scope.from }?.let { "${it.id} · ${it.name}" } ?: "Subtask ${scope.from}"
    }
  }
}

fun changesWindowTitle(item: WorkItem): String =
  "Changes · ${listOfNotNull(item.issueKey, item.title.withoutIssueKey(item.issueKey)).joinToString(" · ")}"

/** Goal titles often start with their own `[KEY]`; the window title already names the key. */
private fun String.withoutIssueKey(key: String?): String =
  if (key != null && startsWith("[$key]")) removePrefix("[$key]").trim() else this

fun changesWindowTitle(source: ChangesSource): String = when (source) {
  is ChangesSource.Work -> changesWindowTitle(source.item)
  is ChangesSource.Chat -> "Changes · ${source.title}"
}
