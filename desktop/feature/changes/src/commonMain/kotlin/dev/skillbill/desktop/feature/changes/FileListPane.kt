package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.SbTag
import dev.skillbill.desktop.core.ui.changes.AddCount
import dev.skillbill.desktop.core.ui.changes.DelCount
import dev.skillbill.desktop.core.ui.changes.changesMono
import dev.skillbill.desktop.core.ui.feedback.readProblemText

@Composable
internal fun FileListPane(state: ChangesWindowUiState, onAction: (ChangesWindowAction) -> Unit, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val listState = rememberLazyListState()
  // j and k move the selection; keep the selected row in view.
  LaunchedEffect(state.selectedPath) {
    val index = lazyIndexOf(state.groups, state.selectedPath ?: return@LaunchedEffect) ?: return@LaunchedEffect
    val shown = listState.layoutInfo.visibleItemsInfo
    if (shown.none { it.index == index && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) listState.scrollToItem(index)
  }
  Box(modifier.background(colors.bgSidePane).hairline(Edge.End, colors.border)) {
    when (val list = state.list) {
      ScopeListUi.Loading -> PaneMessage("Loading changes")
      is ScopeListUi.BaseUnavailable -> PaneMessage("Base commit unavailable: ${list.reason}")
      is ScopeListUi.NotARepository -> PaneMessage("${list.worktreeRoot} is not a git repository")
      is ScopeListUi.TooManyCommits -> PaneMessage("More than ${list.limit} commits to read one by one.")
      is ScopeListUi.Unavailable -> PaneMessage(readProblemText(list.problem))
      is ScopeListUi.Listed -> when {
        list.changes.files.isEmpty() -> PaneMessage("No changes in this scope.")
        state.groups.isEmpty() -> PaneMessage("No files match the filter.")
        else -> LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
          state.groups.forEach { group ->
            item(key = "g:${group.module}") { GroupHeader(group) }
            items(group.rows, key = { "f:${it.file.status}:${it.file.path}" }) { row ->
              FileRow(row, selected = state.selectedPath == row.file.path) { onAction(ChangesWindowAction.SelectFile(row.file.path)) }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun GroupHeader(group: FileGroupUi) {
  Row(
    Modifier.fillMaxWidth().height(29.dp).padding(start = 14.dp, top = 6.dp, end = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(
      group.module.uppercase(),
      Modifier.weight(1f),
      style = changesMono(9.5f, FontWeight.SemiBold, SkillBillTheme.colors.fgFaint).copy(letterSpacing = 0.1.em),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    AddCount(group.insertions)
    DelCount(group.deletions)
  }
}

@Composable
private fun FileRow(row: FileRowUi, selected: Boolean, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val file = row.file
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val background = when {
    selected -> colors.bgOverlay
    hovered -> colors.bgRaised
    else -> Color.Transparent
  }
  val marker = colors.accent
  val tip = if (row.touchCount >= 2) "${file.path}\nChanged by ${row.touchCount} subtasks in this scope" else file.path
  SbTooltip(tip) {
    Row(
      Modifier.fillMaxWidth()
        .background(background)
        .drawBehind { if (selected) drawRect(marker, Offset.Zero, Size(2.dp.toPx(), size.height)) }
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interaction, indication = null, onClick = onClick)
        .height(27.dp)
        .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      BasicText(
        file.status.letter(),
        Modifier.width(16.dp),
        style = changesMono(10f, FontWeight.SemiBold, file.status.statusColor()).copy(textAlign = TextAlign.Center),
        maxLines = 1,
      )
      BasicText(
        file.path.substringAfterLast('/'),
        Modifier.weight(1f),
        style = changesMono(12f, FontWeight.Normal, if (selected) colors.fg else colors.fg2, lineHeight = 15f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row.touchCount >= 2) {
          SbTag("×${row.touchCount}", outlined = true, mono = true)
        }
        if (file.binary) {
          BasicText("binary", style = changesMono(11f, FontWeight.Medium, colors.fgFaint), maxLines = 1, softWrap = false)
        } else {
          AddCount(file.insertions ?: 0)
          DelCount(file.deletions ?: 0)
        }
      }
    }
  }
}

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
