package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.tabular

/** `.sb-prop > dd`: 12 px `--fg-2`; wraps like the cell does. */
@Composable
private fun PaneValue(text: String) {
  BasicText(text, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** `.sb-prop > dd .mono`: 11.5 px mono; `trunc` values keep one line and end in an ellipsis. */
@Composable
private fun PaneMono(text: String, maxLines: Int = 1) {
  BasicText(
    text,
    style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2, fontSize = SkillBillTheme.type.tXs.fontSize),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
  )
}

private fun counts(map: Map<String, Int>): String? =
  map.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.entries?.joinToString(" · ") { "${it.key} ${it.value}" }

/**
 * The details pane (`.sb-pane`): 16/20/24 padding, sections 18 apart, scrolling on its own. Execution paints from the header in
 * the first frame; the loaded facts and runs and fixes fill in after.
 */
@Composable
internal fun DetailsPane(state: DetailUiState) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp),
  ) {
    DetailsPaneSections(state)
  }
}

@Composable
private fun DetailsPaneSections(state: DetailUiState) {
  val item = state.header
  val detail = (state.sections as? SectionsUi.Loaded)?.detail
  val agent = detail?.activeAgent ?: item.agentId
  PaneSection(title = "Execution", separated = false) {
    PropertyList {
      item.control.workflowId?.let { PropertyRow("Workflow", stackedWhenNarrow = true) { PaneMono(it) } }
      item.control.invocationId?.let { PropertyRow("Invocation", stackedWhenNarrow = true) { PaneMono(it) } }
      if (agent != null) {
        PropertyRow("Agent", stackedWhenNarrow = true) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(agent)
            PaneValue(agent)
          }
        }
      }
      item.model?.let { model -> PropertyRow("Model", stackedWhenNarrow = true) { PaneMono(model + item.effort?.let { " · $it" }.orEmpty()) } }
      detail?.executionLiveness?.let { PropertyRow("Liveness", stackedWhenNarrow = true) { PaneValue(it) } }
      item.startedAt?.let { PropertyRow("Started", stackedWhenNarrow = true) { PaneValue(formatInstant(it, "d MMM HH:mm")) } }
      PropertyRow("Last update", stackedWhenNarrow = true) {
        BasicText(formatInstant(item.control.updatedAt, "HH:mm:ss"), style = SkillBillTheme.type.tSm.tabular().copy(color = SkillBillTheme.colors.fg2), maxLines = 1)
      }
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
    PropertyList { runs.forEach { (label, value) -> PropertyRow(label, stackedWhenNarrow = true) { PaneMono(value, maxLines = 3) } } }
  }
}
