package dev.skillbill.desktop.core.domain.model

import dev.skillbill.desktop.core.domain.settings.HistoryWindow

/** One board read: the finished-work window, the runtime's text filter (blank for none), the page size and completeness. */
data class BoardQuery(val window: HistoryWindow, val filter: String, val pageSize: Int, val complete: Boolean = false)

/** The fixed board headers, in display order; each item lands in one by the runtime's live section verdict. */
enum class BoardSectionKind { NeedsYou, Goals, Phases, Operations, Finished }

/** One header's items in the order the runtime returned them. */
data class BoardSection(val kind: BoardSectionKind, val items: List<WorkItem>)

/** Aggregate status of a board, decided by the runtime. */
enum class TrayStatus { Failed, NeedsYou, Running, Idle }

/** The runtime's board counts; the list sizes are those of the runtime's arrangement. */
data class BoardCounts(
  val trayStatus: TrayStatus,
  val running: Int,
  val needsYou: Int,
  val doneWithinDay: Int,
  val goals: Int,
  val livePhases: Int,
  val finishedPhases: Int,
  val liveOperations: Int,
  val finishedOperations: Int,
)

sealed interface BoardState {
  data object Loading : BoardState

  /**
   * [sections] always lists every [BoardSectionKind] in order, including empty ones. [items] is every loaded item in runtime
   * order, for History and the command menu. [stale] means the last re-query failed and these are the previous results.
   */
  data class Loaded(
    val sections: List<BoardSection>,
    val counts: BoardCounts,
    val nextCursor: String?,
    val stale: Boolean,
    val items: List<WorkItem>,
  ) : BoardState

  data class Failed(val problem: ReadProblem) : BoardState
}

sealed interface LoadMoreOutcome {
  data object Appended : LoadMoreOutcome

  data object End : LoadMoreOutcome

  data class Failed(val problem: ReadProblem) : LoadMoreOutcome
}
