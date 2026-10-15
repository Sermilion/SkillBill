package dev.skillbill.tray.feature.history

import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.PagedListState

/** The finished-work list. [filter] is the text typed in the sidebar; the window owns it and passes it to both routes. */
data class HistoryUiState(
  val list: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val filter: String = "",
  val retention: Retention = Retention.DAY,
  val historyFilter: HistoryFilter = HistoryFilter.ALL,
  val pendingFeedback: List<Feedback> = emptyList(),
)

sealed interface HistoryAction {
  data class FilterChanged(val text: String) : HistoryAction
  data class ChooseFilter(val choice: HistoryFilter) : HistoryAction
  data object Retry : HistoryAction
  data object LoadMore : HistoryAction
  data class Select(val identity: WorkIdentity) : HistoryAction
  data class AcknowledgeFeedback(val id: Long) : HistoryAction
}

sealed interface HistoryEffect {
  /** The user picked [identity]; the shell decides where its detail opens. */
  data class OpenDetail(val identity: WorkIdentity) : HistoryEffect
}
