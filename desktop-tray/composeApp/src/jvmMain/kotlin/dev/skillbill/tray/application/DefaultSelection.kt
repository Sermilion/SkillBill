package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.items
import dev.skillbill.tray.feature.history.HistoryUiState
import dev.skillbill.tray.feature.work.WorkUiState

/**
 * What the detail pane opens when nothing is selected: the first live item once every live section has been read cleanly,
 * otherwise the first finished item when no live work is left or still to be paged in. Until then nothing is chosen, so the
 * pane never jumps to a row that a slower section is about to outrank.
 */
fun defaultSelection(work: WorkUiState, history: HistoryUiState): WorkIdentity? {
  val live = listOf(work.needsYou, work.goals, work.phases)
  if (live.any { !it.readCleanly() }) return null
  work.liveItems.firstOrNull()?.let { return it.identity }
  if (work.hasMoreLive) return null
  return history.list.takeIf { it.readCleanly() }?.items?.firstOrNull()?.identity
}

private fun PagedListState<WorkItem>.readCleanly(): Boolean = when (this) {
  is PagedListState.LoadedEmpty -> true
  is PagedListState.Content -> readError == null
  else -> false
}
