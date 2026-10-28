package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import skillbill.ports.idestatus.model.WorkBoardWindow

internal fun HistoryWindow.toWorkBoardWindow(): WorkBoardWindow = when (this) {
  HistoryWindow.SixHours -> WorkBoardWindow.SIX_HOURS
  HistoryWindow.Day -> WorkBoardWindow.TWENTY_FOUR_HOURS
  HistoryWindow.Week -> WorkBoardWindow.SEVEN_DAYS
}
