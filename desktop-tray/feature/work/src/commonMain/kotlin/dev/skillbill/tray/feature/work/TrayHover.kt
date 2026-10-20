package dev.skillbill.tray.feature.work

import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.ui.outcomeLabel

private const val HOVER_ITEMS_PER_CATEGORY = 4

/** The tray's hover text as the small HTML subset the platform tooltip renders; every shown value is length-bounded and escaped. */
fun TrayUiState.trayHover(): String {
  if (!loaded) return "Loading your work…"
  return buildString {
    append("<p><font color=\"#3FB950\">$running running</font> · ")
    append("<font color=\"#E2703C\">$needsYouCount need you</font></p>")
    if (stale) append("<p>Showing stale work data</p>")
    appendHoverCategory("GOALS", popoverGoals)
    append("<hr/>")
    appendHoverCategory("PHASES", popoverLivePhases)
  }
}

private fun StringBuilder.appendHoverCategory(label: String, items: List<WorkItem>) {
  append("<p><font color=\"#9C9CA6\"><b>$label</b></font>")
  if (items.isEmpty()) append("<br/>No active work")
  items.take(HOVER_ITEMS_PER_CATEGORY).forEach { item ->
    val color = when (item.lifecycle) {
      Lifecycle.ACTIVE -> "#3FB950"
      Lifecycle.BLOCKED -> "#E2703C"
      Lifecycle.FAILED -> "#F0555B"
      Lifecycle.CANCELED -> "#B59AF4"
      else -> "#9C9CA6"
    }
    val identifier = item.issueKey ?: item.phaseId ?: item.currentStepId
    val step = if (item.lifecycle == Lifecycle.ACTIVE) item.currentStepLabel.lowercase() else item.outcomeLabel().lowercase()
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
