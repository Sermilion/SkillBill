package dev.skillbill.tray.feature.terminal

/**
 * The tabs in the same order with each label recomputed. A tab the user named shows that name as it is. The others show their
 * project name; those that share one are numbered `name n` in creation order, so a name is only numbered while two or more tabs
 * show it.
 */
internal fun terminalTabLabels(tabs: List<TerminalTab>): List<TerminalTab> {
  val sizes = tabs.filter { it.customName == null }.groupingBy { it.name }.eachCount()
  val seen = mutableMapOf<String, Int>()
  return tabs.map { tab ->
    val custom = tab.customName
    when {
      custom != null -> tab.copy(label = custom)
      sizes.getValue(tab.name) == 1 -> tab.copy(label = tab.name)
      else -> {
        val position = (seen[tab.name] ?: 0) + 1
        seen[tab.name] = position
        tab.copy(label = "${tab.name} $position")
      }
    }
  }
}
