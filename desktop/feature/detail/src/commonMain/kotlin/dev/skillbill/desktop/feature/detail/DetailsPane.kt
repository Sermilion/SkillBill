package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.ShortId
import dev.skillbill.desktop.core.designsystem.detail.Breakdown
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.detail.PropertyText
import dev.skillbill.desktop.core.designsystem.detail.PaneStatus
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.TreeRow
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.status.statusKind

/**
 * The side peek shows the selected row's results first, followed by execution facts. Goal spec files open from the preplan card;
 * they follow the runs only while no loaded subtask has recorded a preplan card to carry them.
 */
@Composable
internal fun DetailsPane(state: DetailUiState, assessmentOrder: MutableState<AssessmentOrder>, onClose: () -> Unit, onAction: (DetailAction) -> Unit) {
  val item = state.header
  val detail = (state.sections as? SectionsUi.Loaded)?.detail
  val previewRun = detail?.let { selectedQaPreview(item, it, state.selectedQaRun, state.subtaskChoice.key) }
  val selected = detail?.let { selectedSubtask(item, it, state.subtaskChoice.key) }
  val selectedSubtaskId = (selected as? SubtaskKey.Subtask)?.id
  val scrollStates = rememberSaveableStateHolder()
  Column(Modifier.fillMaxSize()) {
    SubjectHeader(item, detail, selected, onClose) { onAction(DetailAction.MoveSubtaskSelection(it)) }
    scrollStates.SaveableStateProvider("${item.id}:${state.subtaskChoice.key}") {
      val listState = rememberLazyListState()
      LazyColumn(
        Modifier.weight(1f),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
      ) {
        if (detail != null) {
          item(key = "inspector") {
            when (selected) {
              SubtaskKey.GoalFinish -> GoalFinishPhases(item, detail, state, assessmentOrder, onAction)
              is SubtaskKey.Subtask -> {
                val subtask = detail.subtasks.firstOrNull { it.id == selected.id }
                if (subtask != null) {
                  val phaseResultsPagePending = (DetailList.Phases in (state.sections as? SectionsUi.Loaded)?.moreLists.orEmpty() ||
                    state.listLoads[DetailList.Phases] is ListLoad.Loading) && detail.phaseResults.none { it.subtaskId == subtask.id }
                  SubtaskInspector(item, detail, subtask, state.phaseChoice, state.activity, state.renderedAt, phaseResultsPagePending, state.changes, onAction)
                }
              }
              null -> Unit
            }
          }
          if (state.qaPreviewVisible && selected != SubtaskKey.GoalFinish) {
            previewRun?.let { run ->
              item(key = "qa-preview") { QaPreview(run.invocationId, state.qaPreview) }
            }
          }
        }
        item(key = "execution") {
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
              if (item.kind == WorkKind.Goal && detail.phaseResults.none { results -> results.phaseCards.any { it.phaseId == "preplan" } }) {
                SpecFilesSection(detail, onAction)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SubjectHeader(item: WorkItem, detail: Detail?, selected: SubtaskKey?, onClose: () -> Unit, onMove: (Int) -> Unit) {
  val colors = SkillBillTheme.colors
  val keys = detail?.let { subtaskKeys(item, it) }.orEmpty()
  val index = selected?.let(keys::indexOf) ?: -1
  val status =
    if (detail != null && selected != null) {
      inspectorStatus(item, detail, selected)
    } else {
      item.statusKind().let { PaneStatus(it, statusWord(it)) }
    }
  val title = when (selected) {
    SubtaskKey.GoalFinish -> "Goal finish"
    is SubtaskKey.Subtask -> "Subtask ${subtaskNumber(selected.id)}"
    null -> "Details"
  }
  Row(
    Modifier.fillMaxWidth().height(44.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(start = 20.dp, end = 8.dp),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
    Row(Modifier.weight(1f), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      StatusIcon(status.kind, null)
      Spacer(Modifier.width(10.dp))
      BasicText(
        title, Modifier.weight(1f, fill = false).semantics { heading() },
        style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
      )
      BasicText(status.word, Modifier.padding(start = 8.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted), maxLines = 1)
    }
    if (index >= 0) {
      BasicText("${index + 1}/${keys.size}", style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
      Spacer(Modifier.width(4.dp))
      SbIconButton(LucideIcons.chevronUp, "Previous subject", { onMove(-1) }, enabled = index > 0)
      SbIconButton(LucideIcons.chevronDown, "Next subject", { onMove(1) }, enabled = index < keys.lastIndex)
    }
    SbIconButton(LucideIcons.x, "Close details", onClose)
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
