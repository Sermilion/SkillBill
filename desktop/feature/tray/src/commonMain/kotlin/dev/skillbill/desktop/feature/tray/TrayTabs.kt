package dev.skillbill.desktop.feature.tray

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.sectionSize
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.settings.TrayTab

internal fun BoardState.Loaded.toTrayBoard(): TrayBoard.Loaded {
  val live = items.filter { it.liveSection != null }
  return TrayBoard.Loaded(
    counts = counts,
    running = sectionSize(BoardSectionKind.Goals) + sectionSize(BoardSectionKind.Phases) + sectionSize(BoardSectionKind.Operations),
    needsYou = sectionSize(BoardSectionKind.NeedsYou),
    finishedToday = sectionSize(BoardSectionKind.Finished),
    goals = live.filter { it.kind == WorkKind.Goal },
    livePhases = live.filter { it.kind == WorkKind.Phase },
    finishedPhases = items.filter { it.kind == WorkKind.Phase && it.recentlyFinishedPhase },
    liveOperations = live.filter { it.kind == WorkKind.Operation },
    finishedOperations = items.filter { it.kind == WorkKind.Operation && it.recentlyFinishedPhase },
  )
}

/** The tab attention dot, as library.js draws it: on Goals only, whenever the board's Needs you count is above zero. */
fun TrayBoard.Loaded.attention(tab: TrayTab): Boolean = tab == TrayTab.Goals && needsYou > 0
