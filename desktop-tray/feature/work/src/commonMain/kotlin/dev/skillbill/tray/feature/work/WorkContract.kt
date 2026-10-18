package dev.skillbill.tray.feature.work

import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.ui.PagedListState
import dev.skillbill.tray.core.ui.StoreObservation
import dev.skillbill.tray.core.ui.TrackedSections

/**
 * What the main window's work sidebar renders. Each section is its own [PagedListState], so one section loading, failing or
 * appending never changes another's. The tray and the notifications read their own [TrackedSections]; nothing here is theirs.
 * The sidebar filter text is the window's input, so it is not part of this state.
 */
data class WorkUiState(
  val needsYou: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val goals: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val phases: PagedListState<WorkItem> = PagedListState.InitialLoading,
  /** Work that finished within the last day, latest first; the sidebar lists what finished today. Never live. */
  val finished: PagedListState<WorkItem> = PagedListState.InitialLoading,
  val store: StoreObservation? = null,
) {
  private val sections = TrackedSections(needsYou, goals, phases)

  /** Live work in section order, each item once. */
  val liveItems: List<WorkItem> = sections.liveItems

  /** Whether any live section has been read, so there is a board to show. */
  val loaded: Boolean = sections.loaded

  /** The first read problem of a live section, if there is one. */
  val liveProblem: Problem? = sections.liveProblem

  /** The board on screen is the last successful read, because a live section could not be refreshed. */
  val stale: Boolean = sections.stale

  /** Whether more live work can be requested. */
  val hasMoreLive: Boolean = sections.hasMoreLive
}

sealed interface WorkAction {
  data class FilterChanged(val text: String) : WorkAction
  data object Refresh : WorkAction

  /** Re-reads a section whose first read failed, or whose refresh failed. */
  data class Retry(val section: WorkSection) : WorkAction
  data class LoadMore(val section: WorkSection) : WorkAction
  data class Select(val identity: WorkIdentity) : WorkAction
}

sealed interface WorkEffect {
  /** The user picked [identity]; the shell decides where its detail opens. */
  data class OpenDetail(val identity: WorkIdentity) : WorkEffect
}
