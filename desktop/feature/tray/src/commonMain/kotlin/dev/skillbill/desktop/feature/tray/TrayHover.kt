package dev.skillbill.desktop.feature.tray

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.outcomeLabel

private const val HOVER_ITEMS_PER_CATEGORY = 4

fun TrayUiState.trayHover(): String {
  val board = board as? TrayBoard.Loaded ?: return "Loading your work…"
  return buildString {
    append("<p><font color=\"#3FB950\">${board.running} running</font> · <font color=\"#E2703C\">${board.needsYou} need you</font></p>")
    if (stale) append("<p>Showing stale work data</p>")
    appendCategory("GOALS", board.goals)
    append("<hr/>")
    appendCategory("PHASES", board.livePhases)
    append("<hr/>")
    appendCategory("OPERATIONS", board.liveOperations)
  }
}

private fun StringBuilder.appendCategory(label: String, items: List<WorkItem>) {
  append("<p><font color=\"#9C9CA6\"><b>$label</b></font>")
  if (items.isEmpty()) append("<br/>No active work")
  items.take(HOVER_ITEMS_PER_CATEGORY).forEach { item ->
    val color = when (item.lifecycle) {
      Lifecycle.Active -> if (item.stalledSince != null) "#E9A81C" else "#3FB950"
      Lifecycle.Blocked -> "#E2703C"
      Lifecycle.Failed -> "#F0555B"
      Lifecycle.Canceled -> "#B59AF4"
      else -> "#9C9CA6"
    }
    val identifier = item.issueKey ?: item.control.phaseId ?: item.stepLabel
    val progress = item.progress?.let { " ${item.currentSubtaskId ?: it.done}/${it.total}" }.orEmpty()
    val step = if (item.lifecycle == Lifecycle.Active && item.stalledSince == null) item.phaseExecutionLabel ?: item.stepLabel else item.outcomeLabel().lowercase()
    val repository = item.repositoryRoot.replace('\\', '/').trimEnd('/').substringAfterLast('/').bounded(22)
    append("<br/><font color=\"$color\">●</font> ${identifier.bounded(24).escaped()} · ${(step + progress).bounded(48).escaped()} <font color=\"#9C9CA6\">${repository.escaped()}</font>")
  }
  append("</p>")
}

private fun String.bounded(limit: Int): String = if (length > limit) take(limit - 1) + "…" else this
private fun String.escaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
