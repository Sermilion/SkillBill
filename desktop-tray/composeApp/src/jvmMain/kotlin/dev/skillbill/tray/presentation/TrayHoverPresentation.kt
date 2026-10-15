package dev.skillbill.tray.presentation

import dev.skillbill.tray.application.DashboardState
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.WorkItem

fun presentTrayHover(state: DashboardState): String {
  if (state.board == null) return "Loading your work…"
  val view = presentPopover(state)
  return buildString {
    append("<p><font color=\"#3FB950\">${view.running} running</font> · ")
    append("<font color=\"#E2703C\">${view.needsYou} need you</font></p>")
    if (state.stale) append("<p>Showing stale work data</p>")
    appendHoverCategory("GOALS", view.goals)
    append("<hr/>")
    appendHoverCategory("PHASES", view.livePhases)
  }
}

private fun StringBuilder.appendHoverCategory(label: String, items: List<WorkItem>) {
  append("<p><font color=\"#9C9CA6\"><b>$label</b></font>")
  if (items.isEmpty()) append("<br/>No active work")
  items.take(4).forEach { item ->
    val color = when (item.lifecycle) {
      Lifecycle.ACTIVE -> "#3FB950"
      Lifecycle.BLOCKED -> "#E2703C"
      Lifecycle.FAILED -> "#F0555B"
      Lifecycle.CANCELED -> "#B59AF4"
      else -> "#9C9CA6"
    }
    val identifier = item.issueKey ?: item.phaseId ?: item.currentStepId
    val step = if (item.lifecycle == Lifecycle.ACTIVE) item.currentStepLabel.lowercase() else item.lifecycle.name.lowercase()
    val progress = item.progressTotal?.let { " ${item.progressCompleted ?: 0}/$it" }.orEmpty()
    val execution = item.phaseExecution?.let { " ${it.count}/${it.total ?: "?"}" }.orEmpty()
    val repository = item.repositoryRoot.replace('\\', '/').trimEnd('/').substringAfterLast('/').boundedHoverText(22)
    append("<br/><font color=\"$color\">●</font> ")
    append("${identifier.boundedHoverText(24).htmlEscaped()} · ${(step + progress + execution).boundedHoverText(48).htmlEscaped()}")
    append(" <font color=\"#9C9CA6\">${repository.htmlEscaped()}</font>")
  }
  append("</p>")
}

private fun String.boundedHoverText(limit: Int): String = if (length > limit) take(limit - 1) + "…" else this
private fun String.htmlEscaped(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
