package dev.skillbill.desktop.feature.tray

import dev.skillbill.desktop.core.domain.model.BoardCounts
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.settings.TrayTab

data class TrayUiState(val board: TrayBoard, val tab: TrayTab, val problem: ReadProblem? = null, val stale: Boolean = false)

sealed interface TrayBoard {
  data object Loading : TrayBoard
  data object Failed : TrayBoard
  data class Loaded(
    val counts: BoardCounts,
    val goals: List<WorkItem>,
    val livePhases: List<WorkItem>,
    val finishedPhases: List<WorkItem>,
    val liveOperations: List<WorkItem>,
    val finishedOperations: List<WorkItem>,
  ) : TrayBoard
}

sealed interface TrayAction {
  data class ChooseTab(val tab: TrayTab) : TrayAction
  data class Select(val item: WorkItem) : TrayAction
  data object Refresh : TrayAction
}

sealed interface TrayEffect {
  data class OpenDetail(val item: WorkItem) : TrayEffect
  data object Refresh : TrayEffect
}
