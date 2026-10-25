package dev.skillbill.tray.feature.terminal

/**
 * The tabs in the same order with each label recomputed: `~` for the home directory, else the last path segment, and `/` for a
 * directory made only of separators. Tabs that share a label are numbered `label n` in creation order, so a label is only
 * numbered while its group has two or more members.
 */
internal fun terminalTabLabels(tabs: List<TerminalTab>): List<TerminalTab> {
  val bases = tabs.map(::baseLabel)
  val sizes = bases.groupingBy { it }.eachCount()
  val seen = mutableMapOf<String, Int>()
  return tabs.mapIndexed { index, tab ->
    val base = bases[index]
    if (sizes.getValue(base) == 1) {
      tab.copy(label = base)
    } else {
      val position = (seen[base] ?: 0) + 1
      seen[base] = position
      tab.copy(label = "$base $position")
    }
  }
}

private fun baseLabel(tab: TerminalTab): String =
  if (tab.isHome) "~" else tab.directory.trimEnd('/', '\\').split('/', '\\').last().ifEmpty { "/" }
