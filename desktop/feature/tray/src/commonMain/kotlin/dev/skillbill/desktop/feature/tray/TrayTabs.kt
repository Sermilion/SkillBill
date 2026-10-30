package dev.skillbill.desktop.feature.tray

import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.settings.TrayTab

internal fun BoardState.Loaded.toTrayBoard(): TrayBoard.Loaded {
  val live = items.filter { it.liveSection != null }
  return TrayBoard.Loaded(
    counts = counts,
    goals = live.filter { it.kind == WorkKind.Goal },
    livePhases = live.filter { it.kind == WorkKind.Phase },
    finishedPhases = items.filter { it.kind == WorkKind.Phase && it.recentlyFinishedPhase },
    liveOperations = live.filter { it.kind == WorkKind.Operation },
    finishedOperations = items.filter { it.kind == WorkKind.Operation && it.recentlyFinishedPhase },
  )
}

fun TrayBoard.Loaded.attention(tab: TrayTab): Boolean = when (tab) {
  TrayTab.Goals -> goals.any(WorkItem::requiresAttention)
  TrayTab.Phases -> livePhases.any(WorkItem::requiresAttention)
  TrayTab.Operations -> liveOperations.any(WorkItem::requiresAttention)
}
