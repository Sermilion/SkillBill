package dev.skillbill.desktop.core.ui.board

import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.settings.HistoryWindow

const val WORK_PAGE_SIZE = 50

/** The one board query the sidebar, the command menu, Work with a blank filter and the tray share, so they share one observation. */
object ShellBoard {
  val query = BoardQuery(window = HistoryWindow.Day, filter = "", pageSize = WORK_PAGE_SIZE)
}
