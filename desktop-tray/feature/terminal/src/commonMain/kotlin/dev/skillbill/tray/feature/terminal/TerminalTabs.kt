package dev.skillbill.tray.feature.terminal

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One window's terminal tabs: the list, the active tab, which tab was used most recently, and whether the current opening of the
 * pane has picked its tab. It lives as long as the window, longer than the window's [TerminalViewModel], which a database switch
 * replaces, so a switch keeps every tab and the shell behind it and never picks a tab again while the pane stays open.
 */
class TerminalTabs {
  internal val state = MutableStateFlow(TabState(emptyList(), null, emptyList()))

  // The host reports every composition of an opening; only the first one picks a tab.
  internal var openingApplied = false
}

// [recent] holds tab ids, most recently used first. It stays out of the UI state: only the successor rule and PaneOpened read it.
internal data class TabState(val list: List<TerminalTab>, val activeId: Long?, val recent: List<Long>)
