package dev.skillbill.tray.feature.work

import dev.skillbill.tray.core.domain.model.BoardArrangement
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.TrayStatus
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.ui.Feedback
import dev.skillbill.tray.core.ui.TrackedSections

/** The popover's tabs. [label] is both the tab title and the value persisted in settings. */
enum class PopoverTab(val label: String) {
  GOALS("Goals"),
  PHASES("Phases"),
  OPERATIONS("Operations"),
  ;

  companion object {
    /** The tab saved under [label]; the first tab when settings hold anything else. */
    fun fromLabel(label: String): PopoverTab = entries.firstOrNull { it.label == label } ?: GOALS
  }
}

/**
 * What the tray icon, the macOS status item, the popover and its tooltip render. [sections] is the tray's own unfiltered read
 * of the board; [arrangement] is the runtime's grouping and counts for its tracked items, null until a live section has loaded.
 */
data class TrayUiState(
  val sections: TrackedSections = TrackedSections(),
  val arrangement: BoardArrangement? = null,
  val popoverTab: PopoverTab = PopoverTab.GOALS,
  val pendingFeedback: List<Feedback> = emptyList(),
) {
  val liveProblem: Problem? = sections.liveProblem
  val stale: Boolean = sections.stale
  val loaded: Boolean = sections.loaded
  val hasMoreLive: Boolean = sections.hasMoreLive

  val trayStatus: TrayStatus = arrangement?.trayStatus ?: TrayStatus.IDLE
  val running: Int = arrangement?.running ?: 0
  val needsYouCount: Int = arrangement?.needsYou ?: 0
  val doneWithinDay: Int = arrangement?.doneWithinDay ?: 0

  private val byIdentity = sections.trackedItems.associateBy { it.identity }
  val popoverGoals: List<WorkItem> = arrangement?.goals.orEmpty().mapNotNull(byIdentity::get)
  val popoverLivePhases: List<WorkItem> = arrangement?.livePhases.orEmpty().mapNotNull(byIdentity::get)
  val popoverLiveOperations: List<WorkItem> = arrangement?.liveOperations.orEmpty().mapNotNull(byIdentity::get)
  val popoverFinishedOperations: List<WorkItem> = arrangement?.finishedOperations.orEmpty().mapNotNull(byIdentity::get)
  val popoverFinishedPhases: List<WorkItem> = arrangement?.finishedPhases.orEmpty().mapNotNull(byIdentity::get)
}

sealed interface TrayAction {
  data object Refresh : TrayAction

  /** The popover asks for more of every live section, since it lists them together. */
  data object LoadMoreLive : TrayAction
  data class ChooseTab(val tab: PopoverTab) : TrayAction
  data class Select(val identity: WorkIdentity) : TrayAction
  data class AcknowledgeFeedback(val id: Long) : TrayAction
}

sealed interface TrayEffect {
  /** The user picked [identity]; the shell decides where its detail opens. */
  data class OpenDetail(val identity: WorkIdentity) : TrayEffect
}
