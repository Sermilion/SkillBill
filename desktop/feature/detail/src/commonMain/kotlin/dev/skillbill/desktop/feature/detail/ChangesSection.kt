package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.domain.model.ChangedFile
import dev.skillbill.desktop.core.domain.model.ChangesState
import dev.skillbill.desktop.core.ui.feedback.readProblemText

/** The open item's changed files since its base commit. A click shows a file's diff; a double click opens it. */
internal fun DetailSections.changesSection(changes: ChangesUi, onAction: (DetailAction) -> Unit) {
  val listed = (changes.state as? ChangesState.Listed)?.changes
  section("changes", "Changes", count = listed?.files?.size?.toString()) {
    when (val state = changes.state) {
      ChangesState.Loading -> notice("changes-loading") { Hint("Loading changes") }
      ChangesState.NoSubtask -> notice("changes-none") { Hint("No base branch to diff against yet") }
      is ChangesState.BaseUnavailable -> notice("changes-base") { Hint("Base commit unavailable: ${state.reason}") }
      is ChangesState.NotARepository -> notice("changes-repo") { Hint("${state.worktreeRoot} is not a git repository") }
      is ChangesState.Unavailable -> notice("changes-problem") { Hint(readProblemText(state.problem)) }
      is ChangesState.Listed -> if (state.changes.files.isEmpty()) {
        notice("changes-empty") { Hint("No changes since the base commit") }
      } else {
        val set = state.changes
        item(key = "changes-totals") {
          Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = SkillBillTheme.colors
            val type = SkillBillTheme.type
            BasicText(if (set.files.size == 1) "1 file" else "${set.files.size} files", style = type.tSm.copy(color = colors.fgMuted))
            BasicText("+${set.insertions}", style = type.monoSm.copy(color = colors.stDone))
            BasicText("-${set.deletions}", style = type.monoSm.copy(color = colors.stFailed))
          }
        }
        set.files.forEach { file -> item(key = "change:${file.status}:${file.path}") { ChangedFileRow(file, onAction) } }
      }
    }
  }
}

/** The row and diff header label: `old -> new` for a rename, else the path. */
internal fun ChangedFile.label(): String = if (status == ChangeStatus.Renamed && oldPath != null) "$oldPath -> $path" else path

private fun LazyListScope.notice(key: String, content: @Composable () -> Unit) {
  item(key = key) { Row(Modifier.padding(vertical = 4.dp)) { content() } }
}

@Composable
private fun ChangedFileRow(file: ChangedFile, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val deleted = file.status == ChangeStatus.Deleted
  Row(
    Modifier
      .fillMaxWidth()
      .pointerHoverIcon(PointerIcon.Hand)
      .combinedClickable(
        onClick = { onAction(DetailAction.SelectChangedFile(file)) },
        // A deleted file has nothing on disk to open.
        onDoubleClick = if (deleted) null else { { onAction(DetailAction.OpenChangedFile(file)) } },
      )
      .padding(vertical = 3.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(
      file.label(),
      Modifier.weight(1f, fill = false),
      style = type.monoSm.copy(
        color = if (deleted) colors.fgMuted else colors.fg2,
        textDecoration = if (deleted) TextDecoration.LineThrough else null,
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    when (file.status) {
      ChangeStatus.Deleted -> BasicText("deleted", style = type.tSm.copy(color = colors.fgMuted))
      ChangeStatus.Untracked -> BasicText("untracked", style = type.tSm.copy(color = colors.fgMuted))
      else -> Unit
    }
    Spacer(Modifier.weight(1f))
    if (file.binary) {
      BasicText("bin", style = type.monoSm.copy(color = colors.fgMuted))
    } else {
      BasicText("+${file.insertions ?: 0}", style = type.monoSm.copy(color = colors.stDone))
      BasicText("-${file.deletions ?: 0}", style = type.monoSm.copy(color = colors.stFailed))
    }
  }
}
