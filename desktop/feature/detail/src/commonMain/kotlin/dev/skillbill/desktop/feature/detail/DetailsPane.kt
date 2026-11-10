package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
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
 * The side peek shows the selected row's results first, followed by execution facts and spec files.
 */
@Composable
internal fun DetailsPane(state: DetailUiState, assessmentOrder: MutableState<AssessmentOrder>, onAction: (DetailAction) -> Unit) {
  val collapse = rememberSectionCollapse()
  val item = state.header
  val detail = (state.sections as? SectionsUi.Loaded)?.detail
  val previewRun = detail?.let { selectedQaPreview(item, it, state.selectedQaRun, state.subtaskChoice.key) }
  val selected = detail?.let { selectedSubtask(item, it, state.subtaskChoice.key) }
    ?: SubtaskKey.GoalFinish.takeIf { item.kind == WorkKind.Goal }
  val selectedSubtaskId = (selected as? SubtaskKey.Subtask)?.id
  val scrollStates = rememberSaveableStateHolder()
  Column(Modifier.fillMaxSize()) {
    scrollStates.SaveableStateProvider(if (selected == SubtaskKey.GoalFinish) "finish" else "subtask") {
      val listState = rememberLazyListState()
      LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(24.dp)) {
        val sections = DetailSections(this, collapse, listState, assessmentOrder)
        if (detail != null) {
          sections.item(key = "inspector") { InspectorSection(state, item, detail, selected, assessmentOrder, onAction) }
          if (state.qaPreviewVisible && selected != SubtaskKey.GoalFinish) {
            previewRun?.let { run -> sections.item(key = "qa-preview") { QaPreview(run.invocationId, state.qaPreview) } }
          }
        }

        sections.item(key = "execution") {
          Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ExecutionSection(item, detail, selectedSubtaskId) { onAction(DetailAction.Copy(it)) }
            if (detail != null) {
              val attempts =
                selectedSubtaskId?.let { subtaskId ->
                  val execution = detail.phaseResults.firstOrNull { it.subtaskId == subtaskId }?.execution
                  val counts = execution?.phaseAttemptCounts.orEmpty()
                  DetailAttempts(counts, emptyMap(), emptyMap(), null, 0)
                } ?: detail.attempts
              RunsSection(item.id, attempts)
              if (item.kind == WorkKind.Goal) SpecFilesSection(detail, onAction)
            }
          }
        }
      }
    }
  }
}

private data class ExecutionFacts(
  val workflowId: String?,
  val invocationId: String?,
  val agent: String?,
  val model: String?,
  val effort: String?,
  val liveness: String?,
  val startedAt: kotlin.time.Instant?,
  val updatedAt: kotlin.time.Instant?,
)

private fun executionFacts(item: WorkItem, detail: Detail?, subtaskId: Int?): ExecutionFacts {
  fun String?.present() = this?.takeIf(String::isNotBlank)
  if (subtaskId != null) {
    val execution = detail?.phaseResults?.firstOrNull { it.subtaskId == subtaskId }?.execution
    val liveness = if (subtaskId == detail?.currentSubtaskId) detail?.executionLiveness.present() else null
    return ExecutionFacts(
      workflowId = execution?.workflowId.present(),
      invocationId = null,
      agent = execution?.agentId.present(),
      model = execution?.model.present(),
      effort = execution?.effort.present(),
      liveness = liveness,
      startedAt = execution?.startedAt,
      updatedAt = execution?.updatedAt,
    )
  }
  return ExecutionFacts(
    workflowId = item.control.workflowId.present(),
    invocationId = item.control.invocationId.present(),
    agent = (detail?.activeAgent ?: item.agentId).present(),
    model = item.model.present(),
    effort = item.effort.present(),
    liveness = detail?.executionLiveness.present(),
    startedAt = item.startedAt,
    updatedAt = item.control.updatedAt,
  )
}

@Composable
private fun ExecutionSection(item: WorkItem, detail: Detail?, subtaskId: Int?, onCopy: (String) -> Unit) {
  val facts = executionFacts(item, detail, subtaskId)
  val rowsPresent =
    listOf(
      facts.workflowId,
      facts.invocationId,
      facts.agent,
      facts.model,
      facts.liveness,
      facts.startedAt,
      facts.updatedAt,
    ).any { it != null }
  if (!rowsPresent) return
  PaneSection(title = "Execution") {
    PropertyList {
      facts.workflowId?.let { PropertyRow("Workflow") { ShortId(it, onCopy, label = "workflow id") } }
      facts.invocationId?.let { PropertyRow("Invocation") { ShortId(it, onCopy, label = "invocation id") } }
      facts.agent?.let {
        PropertyRow("Agent") {
          Avatar(it, small = true, tooltip = listOfNotNull(it, facts.model, facts.effort).joinToString(" · "))
          PropertyText(it)
        }
      }
      if (item.control.phaseId == "monitor") {
        PropertyRow("Model") { PropertyText(facts.model ?: "Not reported") }
      } else {
        facts.model?.let { model -> PropertyRow("Model") { PropertyText(model + facts.effort?.let { " · $it" }.orEmpty()) } }
      }
      facts.liveness?.let { PropertyRow("Liveness") { PropertyText(it) } }
      facts.startedAt?.let { PropertyRow("Started") { PropertyText(formatInstant(it, "d MMM HH:mm")) } }
      facts.updatedAt?.let { PropertyRow("Updated") { PropertyText(formatInstant(it, "HH:mm:ss")) } }
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
private fun InspectorSection(state: DetailUiState, item: WorkItem, detail: Detail, chosen: SubtaskKey?, assessmentOrder: MutableState<AssessmentOrder>, onAction: (DetailAction) -> Unit) {
  val key = chosen ?: return
  val status = inspectorStatus(item, detail, key)
  when (key) {
    SubtaskKey.GoalFinish -> PaneSection(title = "Goal finish", status = status) { GoalFinishPhases(item, detail, state, assessmentOrder, onAction) }
    is SubtaskKey.Subtask -> {
      val subtask = detail.subtasks.firstOrNull { it.id == key.id } ?: return
      PaneSection(title = "Subtask ${subtaskNumber(subtask.id)}", status = status) {
        SubtaskInspector(
          item = item,
          detail = detail,
          subtask = subtask,
          phaseChoice = state.phaseChoice,
          activity = state.activity,
          inline = false,
          renderedAt = state.renderedAt,
          onAction = onAction,
        )
      }
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
