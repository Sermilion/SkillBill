package dev.skillbill.desktop.feature.terminal

/** One tab: the [directory] its shell starts in, its default [name] (the project, or `~`) and the user's [customName], if any. */
data class TerminalTab(val id: Long, val directory: String, val name: String, val customName: String? = null)

/** The window's tabs in creation order. It lives in memory only; no tab survives the window. */
data class TerminalTabsState(val tabs: List<TerminalTab> = emptyList(), val activeId: Long? = null, val paneOpen: Boolean = false)

internal fun TerminalTabsState.withTab(tab: TerminalTab): TerminalTabsState = copy(tabs = tabs + tab, activeId = tab.id, paneOpen = true)

/** Trims [name]; a blank name drops the custom name, so the tab shows its default again. */
internal fun TerminalTabsState.renamed(id: Long, name: String): TerminalTabsState =
  copy(tabs = tabs.map { if (it.id == id) it.copy(customName = name.trim().ifEmpty { null }) else it })

internal fun TerminalTabsState.activated(id: Long): TerminalTabsState = if (tabs.any { it.id == id }) copy(activeId = id) else this

/** Closing the active tab activates the tab before it, else the new first one; closing the last tab closes the pane. */
internal fun TerminalTabsState.without(ids: Set<Long>): TerminalTabsState {
  if (tabs.none { it.id in ids }) return this
  val remaining = tabs.filterNot { it.id in ids }
  if (remaining.isEmpty()) return TerminalTabsState()
  val active = activeId?.takeIf { id -> remaining.any { it.id == id } }
    ?: tabs.subList(0, tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)).lastOrNull { it.id !in ids }?.id
    ?: remaining.first().id
  return copy(tabs = remaining, activeId = active)
}

/**
 * Each tab's label. A custom name shows as typed. A default name is numbered `name n` in creation order only while two or more
 * default-named tabs share it; custom-named tabs never count toward that.
 */
fun terminalTabLabels(tabs: List<TerminalTab>): Map<Long, String> {
  val sizes = tabs.filter { it.customName == null }.groupingBy { it.name }.eachCount()
  val seen = mutableMapOf<String, Int>()
  return tabs.associate { tab ->
    val custom = tab.customName
    tab.id to when {
      custom != null -> custom
      sizes.getValue(tab.name) == 1 -> tab.name
      else -> {
        val position = (seen[tab.name] ?: 0) + 1
        seen[tab.name] = position
        "${tab.name} $position"
      }
    }
  }
}
