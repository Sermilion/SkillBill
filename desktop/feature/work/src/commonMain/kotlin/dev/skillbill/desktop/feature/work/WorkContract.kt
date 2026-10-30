package dev.skillbill.desktop.feature.work

import dev.skillbill.desktop.core.domain.model.BoardSection
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import kotlin.time.Instant

/**
 * The Work board. [board] is the runtime's read for [filter]; [runOptions] holds the prefetched options of the focused row and
 * the menu's row only; [reserved] is the app-wide launch reservations; [renderedAt] is when the shown board arrived.
 */
data class WorkUiState(
  val section: BoardSectionKind? = null,
  val filter: String = "",
  val board: BoardState = BoardState.Loading,
  val focusedId: String? = null,
  val runOptions: Map<String, RunOptions> = emptyMap(),
  val reserved: Set<String> = emptySet(),
  val menu: WorkItem? = null,
  val renderedAt: Instant? = null,
) {
  /** Every section when none is chosen, else only the chosen one, in the runtime's order. */
  val shownSections: List<BoardSection>
    get() = (board as? BoardState.Loaded)?.sections.orEmpty().filter { section == null || it.kind == section }

  /** The shown sections' rows flattened in section order; focus moves through these. */
  val visibleRows: List<WorkItem> get() = shownSections.flatMap { it.items }

  fun isReserved(item: WorkItem): Boolean = (item.issueKey ?: item.id) in reserved

  fun canPauseAfterSubtask(item: WorkItem): Boolean = item.kind == WorkKind.Goal && item.pauseEligible && !item.pauseRequested

  fun canPauseNow(item: WorkItem): Boolean = item.kind == WorkKind.Goal && item.controlEligible

  /** False until the row's run options arrive and while a launch reservation holds the item. */
  fun canLaunch(item: WorkItem): Boolean = runOptions[item.id]?.canLaunch == true && !isReserved(item)

  fun canCancel(item: WorkItem): Boolean = runOptions[item.id]?.canCancel == true && !isReserved(item)
}

/** The single-key row controls, acting on the focused row as its context menu entry would. */
enum class RowKey { Pause, PauseNow, Launch }

sealed interface WorkAction {
  data class ShowSection(val section: BoardSectionKind?) : WorkAction
  data class FilterChanged(val text: String) : WorkAction
  data object ClearFilter : WorkAction
  data object Refresh : WorkAction
  data object LoadMore : WorkAction
  data class MoveFocus(val delta: Int) : WorkAction
  data class Focus(val item: WorkItem) : WorkAction
  data class Open(val item: WorkItem) : WorkAction
  data object OpenFocused : WorkAction
  data class OpenMenu(val item: WorkItem) : WorkAction
  data object CloseMenu : WorkAction
  data class PauseAfterSubtask(val item: WorkItem) : WorkAction
  data class PauseNow(val item: WorkItem) : WorkAction
  data class Launch(val item: WorkItem) : WorkAction
  data class Cancel(val item: WorkItem) : WorkAction
  data class FocusedKey(val key: RowKey) : WorkAction
}

sealed interface WorkEffect {
  data class OpenDetail(val item: WorkItem) : WorkEffect
  data class ShowToast(val message: ToastMessage) : WorkEffect
}
