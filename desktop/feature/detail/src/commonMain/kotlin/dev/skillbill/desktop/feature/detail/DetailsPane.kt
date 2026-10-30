package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.Segmented
import dev.skillbill.desktop.core.designsystem.component.SegmentedOption
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import androidx.compose.foundation.text.BasicText
import dev.skillbill.desktop.core.designsystem.component.ShortId
import dev.skillbill.desktop.core.designsystem.detail.Breakdown
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PhasePipeline
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
 * The side peek shows the selected row's results first, followed by execution facts and spec files.
 */
@Composable
internal fun DetailsPane(state: DetailUiState, assessmentOrder: MutableState<AssessmentOrder>, onAction: (DetailAction) -> Unit) {
  val collapse = rememberSectionCollapse()
  val item = state.header
  val detail = (state.sections as? SectionsUi.Loaded)?.detail
  var resultTab by rememberSaveable(item.id) { mutableStateOf("assessment") }
  val selected = detail?.let { selectedSubtask(item, it, state.subtaskChoice.key) }
    ?: SubtaskKey.GoalFinish.takeIf { item.kind == WorkKind.Goal }
  val scrollStates = rememberSaveableStateHolder()
  Column(Modifier.fillMaxSize()) {
    if (selected == SubtaskKey.GoalFinish) {
      Segmented(
        listOf(SegmentedOption("assessment", "Assessment"), SegmentedOption("qa", "QA")),
        resultTab,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
      ) { resultTab = it }
    }
    scrollStates.SaveableStateProvider(if (selected == SubtaskKey.GoalFinish) resultTab else "subtask") {
      LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp)) {
        val sections = DetailSections(this, collapse, assessmentOrder)
        if (detail != null) {
          sections.item(key = "inspector") { InspectorSection(item, detail, selected, onAction) }
          if (selected == SubtaskKey.GoalFinish) {
            if (resultTab == "assessment") {
              if (detail.goalAssessment != null) sections.goalAssessmentSection(detail) else sections.item(key = "assessment-unavailable") {
                MissingResult("Goal assessment", "No assessment result is available for this run.")
              }
            } else {
              if (detail.goalQa != null) sections.goalQaSection(detail) else sections.item(key = "qa-unavailable") {
                MissingResult("Goal QA", "No QA result is available for this run.")
              }
            }
          }
        }
        sections.item(key = "execution") {
          Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ExecutionSection(item, detail) { onAction(DetailAction.Copy(it)) }
            if (detail != null) {
              RunsSection(item.id, detail.attempts)
              if (item.kind == WorkKind.Goal) SpecFilesSection(detail, onAction)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MissingResult(title: String, message: String) {
  PaneSection(title = title) {
    SbBox {
      BasicText(message, Modifier.padding(16.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
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
private fun InspectorSection(item: WorkItem, detail: Detail, chosen: SubtaskKey?, onAction: (DetailAction) -> Unit) {
  val key = chosen ?: return
  val status = inspectorStatus(item, detail, key)
  when (key) {
    SubtaskKey.GoalFinish -> PaneSection(title = "Goal finish", status = status) { PhasePipeline(goalFinishSteps(item, detail)) }
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
