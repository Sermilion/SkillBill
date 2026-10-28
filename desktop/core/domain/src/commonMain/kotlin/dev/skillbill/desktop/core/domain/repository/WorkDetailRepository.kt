package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.Flow

/** One item's detail: the header paints from the row at once, the sections load once and then refresh in place. */
interface WorkDetailRepository {
  fun observe(item: WorkItem): Flow<DetailState>

  /** Appends the next page of [list] to the open detail of [id]; [anchor] is that list's token from `DetailSections.Loaded.listCursors`. */
  suspend fun loadList(id: String, list: DetailList, anchor: String?): DetailListOutcome

  /** Runs the first load for a focused row ahead of opening it, and returns its run options for the context menu. */
  suspend fun prefetch(id: String): RunOptions?
}
