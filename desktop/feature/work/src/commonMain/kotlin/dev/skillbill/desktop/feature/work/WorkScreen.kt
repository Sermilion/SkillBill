package dev.skillbill.desktop.feature.work

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbInput
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.list.groupHeader
import dev.skillbill.desktop.core.designsystem.navigation.Crumb
import dev.skillbill.desktop.core.designsystem.navigation.ViewHeader
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.feedback.StaleIndicator
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.input.gatesShortcutsWhileFocused

private val FILTER_WIDTH = 280.dp

internal fun sectionLabel(kind: BoardSectionKind): String = when (kind) {
  BoardSectionKind.NeedsYou -> "Needs you"
  BoardSectionKind.Goals -> "Goal runs"
  BoardSectionKind.Phases -> "Standalone phases"
  BoardSectionKind.Operations -> "Operations"
  BoardSectionKind.Finished -> "Finished today"
}

/** An empty section's note, worded as the prototype and `desktop-tray`'s work contract word it. */
internal fun emptySectionText(kind: BoardSectionKind, filtering: Boolean): String = when {
  filtering -> "No matching work"
  kind == BoardSectionKind.Finished -> "Nothing finished today"
  else -> "No ${sectionLabel(kind).lowercase()}"
}

private fun sectionLead(kind: BoardSectionKind): StatusKind = when (kind) {
  BoardSectionKind.NeedsYou -> StatusKind.Blocked
  BoardSectionKind.Finished -> StatusKind.Completed
  BoardSectionKind.Goals, BoardSectionKind.Phases, BoardSectionKind.Operations -> StatusKind.Active
}

/** Pane toggles and the shell actions a Work row reaches without depending on the shell. */
internal class WorkScreenHost(
  val specsOpen: Boolean,
  val terminalOpen: Boolean,
  val onToggleSpecs: () -> Unit,
  val onToggleTerminal: () -> Unit,
  val onShowAllWork: () -> Unit,
  val onOpenTerminal: (WorkItem) -> Unit,
  val onCopyReason: (String) -> Unit,
)

@Composable
internal fun WorkScreen(state: WorkUiState, filterFocus: FocusRequester, host: WorkScreenHost, onAction: (WorkAction) -> Unit) {
  val section = state.section
  val crumbs = if (section == null) listOf(Crumb("Work")) else listOf(Crumb("Work", onClick = host.onShowAllWork), Crumb(sectionLabel(section)))
  Column(Modifier.fillMaxSize()) {
    ViewHeader(
      crumbs = crumbs,
      middle = {
        SbInput(
          state.filter,
          { onAction(WorkAction.FilterChanged(it)) },
          Modifier.width(FILTER_WIDTH).focusRequester(filterFocus).gatesShortcutsWhileFocused(),
          placeholder = "Filter work",
          leadingIcon = LucideIcons.search,
        )
      },
      actions = {
        if ((state.board as? BoardState.Loaded)?.stale == true) StaleIndicator()
        SbIconButton(LucideIcons.refresh, "Refresh", { onAction(WorkAction.Refresh) })
        SbIconButton(LucideIcons.fileText, if (host.specsOpen) "Close specs pane" else "Specs", host.onToggleSpecs, selected = host.specsOpen)
        SbIconButton(LucideIcons.terminal, if (host.terminalOpen) "Close terminal" else "Terminal", host.onToggleTerminal, selected = host.terminalOpen)
      },
    )
    Box(Modifier.weight(1f)) {
      when (val board = state.board) {
        BoardState.Loading -> Centered { EmptyState("Loading your work", LucideIcons.inbox) }
        is BoardState.Failed -> Centered { EmptyState(readProblemText(board.problem), LucideIcons.alert) }
        is BoardState.Loaded -> WorkBody(state, board, host, onAction)
      }
    }
  }
}

@Composable
private fun WorkBody(state: WorkUiState, board: BoardState.Loaded, host: WorkScreenHost, onAction: (WorkAction) -> Unit) {
  val filtering = state.filter.isNotBlank()
  val sections = state.shownSections
  if (filtering && sections.all { it.items.isEmpty() }) {
    LaunchedEffect(board.nextCursor) { if (board.nextCursor != null) onAction(WorkAction.LoadMore) }
    Centered {
      EmptyState("No matching work", LucideIcons.search, body = "Nothing on the board matches the filter.") {
        SbButton("Clear filter", { onAction(WorkAction.ClearFilter) })
      }
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
  LaunchedEffect(atEnd, board.nextCursor) { if (atEnd && board.nextCursor != null) onAction(WorkAction.LoadMore) }
  LazyColumn(Modifier.fillMaxSize(), listState) {
    sections.forEach { boardSection ->
      val kind = boardSection.kind
      groupHeader(key = "header:$kind", label = sectionLabel(kind), count = boardSection.items.size, lead = { StatusIcon(sectionLead(kind), 0.5f) })
      items(boardSection.items, key = { "$kind:${it.id}" }) { item ->
        Box {
          WorkRow(item, focused = item.id == state.focusedId, renderedAt = state.renderedAt, onAction = onAction)
          if (state.menu?.id == item.id) WorkRowMenu(state, item, onAction, host.onOpenTerminal, host.onCopyReason)
        }
      }
      if (boardSection.items.isEmpty()) {
        item(key = "empty:$kind") { EmptyState(emptySectionText(kind, filtering), LucideIcons.checkCircle) }
      }
    }
  }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
