package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.domain.model.ChangeStatus
import dev.skillbill.desktop.core.ui.feedback.readProblemText

@Composable
internal fun FileListPane(
  state: ChangesWindowUiState,
  onAction: (ChangesWindowAction) -> Unit,
  filterFocus: FocusRequester,
  onFilterFocused: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  // j and k move the selection; keep the selected row in view.
  LaunchedEffect(state.selectedPath) {
    val index = lazyIndexOf(state.groups, state.selectedPath ?: return@LaunchedEffect) ?: return@LaunchedEffect
    val shown = listState.layoutInfo.visibleItemsInfo
    if (shown.none { it.index == index && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) listState.scrollToItem(index)
  }
  Column(modifier.padding(10.dp)) {
    SbInput(
      state.filter,
      { onAction(ChangesWindowAction.SetFilter(it)) },
      Modifier.fillMaxWidth().focusRequester(filterFocus).onFocusChanged { onFilterFocused(it.hasFocus) },
      placeholder = "Filter files",
    )
    when (val list = state.list) {
      ScopeListUi.Loading -> Hint("Loading changes")
      is ScopeListUi.BaseUnavailable -> Hint("Base commit unavailable: ${list.reason}")
      is ScopeListUi.NotARepository -> Hint("${list.worktreeRoot} is not a git repository")
      is ScopeListUi.TooManyCommits -> Hint("More than ${list.limit} commits outside subtasks.")
      is ScopeListUi.Unavailable -> Hint(readProblemText(list.problem))
      is ScopeListUi.Listed -> when {
        list.changes.files.isEmpty() -> Hint("No changes in this scope.")
        state.groups.isEmpty() -> Hint("No files match the filter.")
        else -> LazyColumn(state = listState) {
          state.groups.forEach { group ->
            item(key = "g:${group.module}") {
              Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(group.module, Modifier.weight(1f), style = SkillBillTheme.type.tSmMedium)
                BasicText("+${group.insertions}", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.stDone))
                BasicText("-${group.deletions}", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.stFailed))
              }
            }
            items(group.rows, key = { "f:${it.file.status}:${it.file.path}" }) { row ->
              val file = row.file
              Row(
                Modifier.fillMaxWidth().background(if (state.selectedPath == file.path) SkillBillTheme.colors.accentTint else Color.Transparent).clickable { onAction(ChangesWindowAction.SelectFile(file.path)) }.padding(vertical = 5.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                BasicText(file.status.letter(), style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted))
                SbTooltip(file.path) { BasicText(file.path.substringAfterLast('/'), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (file.binary) BasicText("binary", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted)) else {
                  BasicText("+${file.insertions ?: 0}", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.stDone))
                  BasicText("-${file.deletions ?: 0}", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.stFailed))
                }
                if (row.touchCount >= 2) SbTooltip("Changed by ${row.touchCount} subtasks in this scope") { BasicText("x${row.touchCount}", style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fgMuted)) }
              }
            }
          }
        }
      }
    }
  }
}

internal fun ChangeStatus.letter() = when (this) { ChangeStatus.Added -> "A"; ChangeStatus.Modified -> "M"; ChangeStatus.Deleted -> "D"; ChangeStatus.Renamed -> "R"; ChangeStatus.Untracked -> "U" }

/** The lazy list index of [path]'s row; every group contributes its header item before its rows. */
private fun lazyIndexOf(groups: List<FileGroupUi>, path: String): Int? {
  var index = 0
  groups.forEach { group ->
    index++
    group.rows.forEach { row ->
      if (row.file.path == path) return index
      index++
    }
  }
  return null
}
