package dev.skillbill.desktop.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillSpace
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.ViewTab
import dev.skillbill.desktop.core.designsystem.component.ViewTabs
import dev.skillbill.desktop.core.designsystem.component.dataColorFor
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.list.ListRow
import dev.skillbill.desktop.core.designsystem.list.groupHeader
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import dev.skillbill.desktop.core.ui.feedback.StaleIndicator
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused
import dev.skillbill.desktop.core.ui.shell.LocalShellLayout
import dev.skillbill.desktop.core.ui.status.statusKind

private val FILTER_WIDTH = 200.dp
private val DURATION_WIDTH = 56.dp

/** The retention as Settings words it ("6h", "24h", "7d"), for "Finished within 24h". */
internal fun retentionText(window: HistoryWindow): String = when (window) {
  HistoryWindow.SixHours -> "6h"
  HistoryWindow.Day -> "24h"
  HistoryWindow.Week -> "7d"
}

/** The id a row shows: the issue key for goals, the phase or operation id otherwise, as the prototype's `rowKey`. */
private fun rowId(item: WorkItem): String =
  if (item.kind == WorkKind.Goal) item.issueKey ?: item.id else item.control.phaseId ?: item.issueKey ?: item.id

private fun kindCaption(kind: WorkKind?): String? = when (kind) {
  WorkKind.Phase -> "standalone phase"
  WorkKind.Operation -> "operation"
  WorkKind.Goal, null -> null
}

/** Pane toggles the History header reaches without depending on the shell. */
internal class HistoryScreenHost(val specsOpen: Boolean, val terminalOpen: Boolean, val onToggleSpecs: () -> Unit, val onToggleTerminal: () -> Unit)

@Composable
internal fun HistoryScreen(state: HistoryUiState, filterFocus: FocusRequester, host: HistoryScreenHost, onAction: (HistoryAction) -> Unit) {
  // Narrow windows drop the header's middle slot, as `.sb-vh-mid` hides below 760 px.
  val narrow = LocalShellLayout.current.drawerMode
  Column(Modifier.fillMaxSize()) {
    ViewHeader(
      crumbs = listOf(Crumb("History")),
      middle = if (narrow) null else {
        {
          Row(horizontalArrangement = Arrangement.spacedBy(SkillBillSpace.s3), verticalAlignment = Alignment.CenterVertically) {
            ViewTabs(HistoryTab.entries.map { ViewTab(it, it.label) }, state.tab) { onAction(HistoryAction.ChooseTab(it)) }
            SbInput(
              state.filter,
              { onAction(HistoryAction.FilterChanged(it)) },
              Modifier.width(FILTER_WIDTH).focusRequester(filterFocus).gatesShortcutsWhileFocused(),
              placeholder = "Filter history",
              leadingIcon = LucideIcons.search,
            )
          }
        }
      },
      actions = {
        if ((state.board as? BoardState.Loaded)?.stale == true) StaleIndicator()
        SbIconButton(LucideIcons.refresh, "Refresh", { onAction(HistoryAction.Refresh) })
        SbIconButton(LucideIcons.fileText, if (host.specsOpen) "Close specs pane" else "Specs", host.onToggleSpecs, shortcut = "G S", selected = host.specsOpen)
        SbIconButton(LucideIcons.terminal, if (host.terminalOpen) "Close terminal" else "Terminal", host.onToggleTerminal, shortcut = "⌃ `", selected = host.terminalOpen)
      },
    )
    Box(Modifier.weight(1f)) {
      when (val board = state.board) {
        BoardState.Loading -> Centered { EmptyState("Loading your work", LucideIcons.inbox) }
        is BoardState.Failed -> Centered { EmptyState(readProblemText(board.problem), LucideIcons.alert) }
        is BoardState.Loaded -> HistoryBody(state, board, narrow, onAction)
      }
    }
  }
}

@Composable
private fun HistoryBody(state: HistoryUiState, board: BoardState.Loaded, narrow: Boolean, onAction: (HistoryAction) -> Unit) {
  val retention = retentionText(state.window)
  val rows = state.rows
  if (rows.isEmpty()) {
    LaunchedEffect(board.nextCursor) { if (board.nextCursor != null) onAction(HistoryAction.LoadMore) }
    Centered {
      if (state.filter.isNotBlank()) EmptyState("No matching work", LucideIcons.history)
      else EmptyState("No finished work", LucideIcons.history, body = "Nothing finished within $retention.")
    }
    return
  }
  val listState = rememberLazyListState()
  val atEnd by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
    }
  }
  LaunchedEffect(atEnd, board.nextCursor) { if (atEnd && board.nextCursor != null) onAction(HistoryAction.LoadMore) }
  LazyColumn(Modifier.fillMaxSize(), listState) {
    groupHeader(key = "header", label = "Finished within $retention", count = rows.size)
    items(rows, key = { it.id }) { item ->
      Box {
        HistoryRow(item, focused = item.id == state.focusedId, state, narrow, onAction)
        if (state.menu?.id == item.id) {
          val close = { onAction(HistoryAction.CloseMenu) }
          ShellLayer(close)
          SbPopover(expanded = true, onDismissRequest = close) {
            SbMenu(listOf(SbMenuEntry.Item("Open", { onAction(HistoryAction.Open(item)); close() }, icon = LucideIcons.external, shortcut = "↵")))
          }
        }
      }
    }
  }
}

@Composable
private fun HistoryRow(item: WorkItem, focused: Boolean, state: HistoryUiState, narrow: Boolean, onAction: (HistoryAction) -> Unit) {
  val finishedAt = item.finishedAt
  val renderedAt = state.renderedAt
  ListRow(
    title = item.title,
    onClick = { onAction(HistoryAction.Open(item)) },
    id = rowId(item),
    status = { StatusIcon(item.statusKind(), null) },
    summary = kindCaption(item.kind),
    meta = {
      if (!narrow) SbLabel(item.repositoryName, SbLabelStyle.Dot(SkillBillTheme.colors.dataColorFor(item.repositoryName)))
      // Total run time: a faint 56 dp right-aligned cell, as the prototype's history meta.
      Box(Modifier.width(DURATION_WIDTH), contentAlignment = Alignment.CenterEnd) {
        CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fgFaint) {
          SbClock(item.activeDurationMs, item.activeDurationAsOf, live = false)
        }
      }
    },
    time = if (finishedAt != null && renderedAt != null) finishedTimeText(finishedAt, renderedAt) else null,
    focused = focused,
    narrow = narrow,
    onContextMenu = { _: Offset -> onAction(HistoryAction.OpenMenu(item)) },
  )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
