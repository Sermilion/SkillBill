package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow

@Composable
private fun PaneValue(text: String) {
  BasicText(text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 2)
}

private fun counts(map: Map<String, Int>): String? =
  map.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.entries?.joinToString(" · ") { "${it.key} ${it.value}" }

/** The details pane. Execution paints from the header in the first frame; the loaded facts and runs and fixes fill in after. */
@Composable
internal fun DetailsPane(state: DetailUiState) {
  val item = state.header
  val detail = (state.sections as? SectionsUi.Loaded)?.detail
  val agent = detail?.activeAgent ?: item.agentId
  val ids = listOfNotNull(item.control.workflowId?.let { "Workflow" to it }, item.control.invocationId?.let { "Invocation" to it })
  val facts = listOfNotNull(
    item.model?.let { "Model" to it + item.effort?.let { effort -> " · $effort" }.orEmpty() },
    detail?.executionLiveness?.let { "Liveness" to it },
    item.startedAt?.let { "Started" to formatInstant(it, "d MMM HH:mm") },
    "Last update" to formatInstant(item.control.updatedAt, "HH:mm:ss"),
  )
  PaneSection(title = "Execution", separated = false) {
    PropertyList {
      ids.forEach { (label, value) -> PropertyRow(label) { PaneValue(value) } }
      if (agent != null) {
        PropertyRow("Agent") {
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(agent, small = true)
            PaneValue(agent)
          }
        }
      }
      facts.forEach { (label, value) -> PropertyRow(label) { PaneValue(value) } }
    }
  }
  val attempts = detail?.attempts ?: return
  val runs = listOfNotNull(
    counts(attempts.phaseAttempts)?.let { "Phase runs" to it },
    counts(attempts.fixIterations)?.let { "Fix iterations" to it },
    attempts.findingsInScope?.let { "Findings in scope" to it.toString() },
    counts(attempts.reAttemptCauses)?.let { "Relaunch causes" to it },
    attempts.supervisorKills.takeIf { it > 0 }?.let { "Supervisor kills" to it.toString() },
  )
  if (runs.isEmpty()) return
  PaneSection(title = "Runs and fixes") {
    PropertyList { runs.forEach { (label, value) -> PropertyRow(label) { PaneValue(value) } } }
  }
}
