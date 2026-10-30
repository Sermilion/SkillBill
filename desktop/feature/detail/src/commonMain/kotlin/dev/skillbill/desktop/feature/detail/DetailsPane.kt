package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.ShortId
import dev.skillbill.desktop.core.designsystem.detail.Breakdown
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.detail.PropertyText
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.TreeRow
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind

/**
 * The details pane (`.sb-pane`): one scroll, 16/20/32 padding, sections 24 apart with no dividers. Execution paints from the
 * header in the first frame; runs and fixes, the subtask inspector and the spec files fill in once the sections load.
 */
@Composable
internal fun DetailsPane(state: DetailUiState, onAction: (DetailAction) -> Unit, onJump: (String) -> Unit) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    val item = state.header
    val detail = (state.sections as? SectionsUi.Loaded)?.detail
    ExecutionSection(item, detail) { onAction(DetailAction.Copy(it)) }
    if (detail != null) {
      RunsSection(item.id, detail.attempts)
      InspectorSection(item, detail, state.subtaskChoice.key, onAction, onJump)
      if (item.kind == WorkKind.Goal) SpecFilesSection(detail, onAction)
    }
  }
}

@Composable
private fun ExecutionSection(item: WorkItem, detail: Detail?, onCopy: (String) -> Unit) {
  val agent = detail?.activeAgent ?: item.agentId
  PaneSection(title = "Execution") {
    PropertyList {
      item.control.workflowId?.let { PropertyRow("Workflow") { ShortId(it, onCopy, label = "workflow id") } }
      item.control.invocationId?.let { PropertyRow("Invocation") { ShortId(it, onCopy, label = "invocation id") } }
      agent?.takeIf { it.isNotBlank() }?.let {
        PropertyRow("Agent") {
          Avatar(it, small = true, tooltip = listOfNotNull(it, item.model, item.effort).joinToString(" · "))
          PropertyText(it)
        }
      }
      item.model?.takeIf { it.isNotBlank() }?.let { model -> PropertyRow("Model") { PropertyText(model + item.effort?.let { " · $it" }.orEmpty()) } }
      detail?.executionLiveness?.takeIf { it.isNotBlank() }?.let { PropertyRow("Liveness") { PropertyText(it) } }
      item.startedAt?.let { PropertyRow("Started") { PropertyText(formatInstant(it, "d MMM HH:mm")) } }
      PropertyRow("Updated") { PropertyText(formatInstant(item.control.updatedAt, "HH:mm:ss")) }
    }
  }
}

/** A total with its Breakdown under it, or nothing when the total is zero. */
@Composable
private fun TotalRow(label: String, counts: Map<String, Int>, open: Boolean, onToggle: () -> Unit) {
  val total = counts.values.filter { it > 0 }.sum()
  if (total == 0) return
  val keyed = counts.size > 1 || counts.keys.any { it.isNotBlank() }
  PropertyRow(label, sub = if (keyed) { { Breakdown(counts, open, onToggle) } } else null) { PropertyText(total.toString()) }
}

/** Runs and fixes: Runs, Fixes and Relaunches with their breakdowns, then Findings and Kills; left out when every row is. */
@Composable
private fun RunsSection(itemId: String, attempts: DetailAttempts) {
  val findings = attempts.findingsInScope?.takeIf { it > 0 }
  val kills = attempts.supervisorKills.takeIf { it > 0 }
  val totals = listOf(attempts.phaseAttempts, attempts.fixIterations, attempts.reAttemptCauses)
  if (totals.all { counts -> counts.values.none { it > 0 } } && findings == null && kills == null) return
  // screens.js keys an open breakdown by item (`bdOpen` holds `${it.id}:${key}`), so paging never carries one open.
  val open = remember(itemId) { mutableStateMapOf<String, Boolean>() }
  fun toggle(key: String) { open[key] = open[key] != true }
  PaneSection(title = "Runs and fixes") {
    PropertyList {
      TotalRow("Runs", attempts.phaseAttempts, open["runs"] == true) { toggle("runs") }
      TotalRow("Fixes", attempts.fixIterations, open["fixes"] == true) { toggle("fixes") }
      TotalRow("Relaunches", attempts.reAttemptCauses, open["relaunches"] == true) { toggle("relaunches") }
      findings?.let { PropertyRow("Findings", tooltip = "Findings in scope") { PropertyText(it.toString()) } }
      kills?.let { PropertyRow("Kills", tooltip = "Supervisor kills") { PropertyText(it.toString()) } }
    }
  }
}

/** The selected subtask, or the Goal finish steps; goals with a subtask index only. */
@Composable
private fun InspectorSection(item: WorkItem, detail: Detail, chosen: SubtaskKey?, onAction: (DetailAction) -> Unit, onJump: (String) -> Unit) {
  val key = selectedSubtask(item, detail, chosen) ?: return
  val status = inspectorStatus(item, detail, key)
  when (key) {
    SubtaskKey.GoalFinish -> PaneSection(title = "Goal finish", status = status) { GoalFinishInspector(item, detail, onJump) }
    is SubtaskKey.Subtask -> {
      val subtask = detail.subtasks.firstOrNull { it.id == key.id } ?: return
      PaneSection(title = "Subtask ${subtaskNumber(subtask.id)}", status = status) { SubtaskInspector(detail, subtask, inline = false, onAction) }
    }
  }
}

/** The goal-level spec files (manifest, spec.md, then by name) as 28 px file rows; subtask specs open from the inspector. */
@Composable
private fun SpecFilesSection(detail: Detail, onAction: (DetailAction) -> Unit) {
  val files = goalSpecFiles(detail)
  if (files.isEmpty()) return
  PaneSection(title = "Spec files") {
    files.forEach { path ->
      val name = fileName(path)
      TreeRow(
        title = name,
        onClick = { onAction(DetailAction.OpenSpecFile(path)) },
        file = true,
        inPane = true,
        lead = { SbIcon(if (name.endsWith(".yaml") || name.endsWith(".yml")) LucideIcons.file else LucideIcons.fileText, SbIconSize.Sm) },
      )
    }
  }
}
