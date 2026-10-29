package dev.skillbill.desktop.feature.history

import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import kotlin.time.Instant

enum class HistoryTab(val label: String, val kind: WorkKind?) {
  All("All", null),
  Goals("Goals", WorkKind.Goal),
  Phases("Phases", WorkKind.Phase),
  Operations("Operations", WorkKind.Operation),
}

/** Finished work within [window]: the runtime's board read for [filter], narrowed to finished items of the [tab]'s kind. */
data class HistoryUiState(
  val tab: HistoryTab = HistoryTab.All,
  val filter: String = "",
  val window: HistoryWindow = HistoryWindow.DEFAULT,
  val board: BoardState = BoardState.Loading,
  val focusedId: String? = null,
  val menu: WorkItem? = null,
  val renderedAt: Instant? = null,
) {
  /** The loaded items whose run finished, of the tab's kind, in runtime order; a stale board keeps its rows. */
  val rows: List<WorkItem>
    get() = (board as? BoardState.Loaded)?.items.orEmpty().filter { it.finishedAt != null && (tab.kind == null || it.kind == tab.kind) }
}

sealed interface HistoryAction {
  data class FilterChanged(val text: String) : HistoryAction
  data class ChooseTab(val tab: HistoryTab) : HistoryAction
  data object Refresh : HistoryAction
  data object LoadMore : HistoryAction
  data class MoveFocus(val delta: Int) : HistoryAction
  data class Open(val item: WorkItem) : HistoryAction
  data object OpenFocused : HistoryAction
  data class OpenMenu(val item: WorkItem) : HistoryAction
  data object CloseMenu : HistoryAction
}

sealed interface HistoryEffect {
  data class OpenDetail(val item: WorkItem) : HistoryEffect
}
