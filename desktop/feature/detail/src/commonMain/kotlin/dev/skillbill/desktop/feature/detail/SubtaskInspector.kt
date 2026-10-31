package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular
import dev.skillbill.desktop.core.designsystem.component.ExpandableText
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.ShortId
import dev.skillbill.desktop.core.designsystem.detail.PaneStatus
import dev.skillbill.desktop.core.designsystem.detail.PipelineStepState
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.detail.PropertyText
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.SubtaskChip
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.WorkDependency
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.ui.status.subtaskStatusKind

/** Result section keys shared by the document and the side peek. */
internal const val GOAL_QA_KEY = "goal-qa"
internal const val GOAL_ASSESSMENT_KEY = "goal-assessment"

/** components.js `VERB`: a subtask state's word in the pane title. */
internal fun statusWord(kind: StatusKind): String = when (kind) {
  StatusKind.Completed -> "Done"
  StatusKind.CompletedNoChange -> "No change"
  StatusKind.Active, StatusKind.Stalled, StatusKind.Planning -> "In progress"
  StatusKind.Blocked -> "Blocked"
  StatusKind.Pending -> "Pending"
  StatusKind.Skipped -> "Skipped"
  StatusKind.Paused -> "Paused"
  StatusKind.Failed -> "Failed"
  StatusKind.Canceled -> "Canceled"
}

/** The Goal finish row's state: done once the goal finished, else pending. */
internal fun goalFinishStatus(item: WorkItem): StatusKind = if (finishedAt(item) != null) StatusKind.Completed else StatusKind.Pending

/** The pane title's status for the selected row. */
internal fun inspectorStatus(item: WorkItem, detail: Detail, key: SubtaskKey): PaneStatus = when (key) {
  SubtaskKey.GoalFinish -> goalFinishStatus(item).let { PaneStatus(it, statusWord(it)) }
  is SubtaskKey.Subtask -> {
    val kind = subtaskStatusKind(detail.subtasks.firstOrNull { it.id == key.id }?.status.orEmpty())
    PaneStatus(kind, statusWord(kind), 0.5f)
  }
}

/** HANDOFF 9.2 "Subtask inspector": the selected subtask; [inline] drops the title, which the row already shows. */
@Composable
internal fun SubtaskInspector(detail: Detail, subtask: WorkSubtask, inline: Boolean, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (!inline) BasicText(subtask.name, style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg, lineHeight = 1.4.em))
    subtask.blockedReason?.takeIf { it.isNotBlank() }?.let { Hint(it, FeedbackTone.Warn, fold = true) }
    SubtaskProperties(detail, subtask, onAction)
    SubtaskReview(detail, subtask.id)
  }
}

@Composable
private fun SubtaskProperties(detail: Detail, subtask: WorkSubtask, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val depends = dependenciesOf(detail, subtask.id)
  val needed = neededBy(detail, subtask.id)
  val validation = detail.validation.firstOrNull { it.subtaskId == subtask.id }
  val spec = subtaskSpecPath(detail, subtask.id)
  PropertyList {
    subtask.commitSha?.takeIf { it.isNotBlank() }?.let { sha ->
      PropertyRow("Commit") { ShortId(sha, { onAction(DetailAction.Copy(it)) }, label = "commit") }
    }
    spec?.let { path ->
      PropertyRow("Spec file", onClick = { onAction(DetailAction.OpenSpecFile(path)) }, tooltip = "Open the spec file", shortcut = "↵") {
        CompositionLocalProvider(LocalContentColor provides colors.fgFaint) { SbIcon(LucideIcons.fileText, SbIconSize.Sm) }
        PropertyText(fileName(path))
      }
    }
    if (depends.isNotEmpty()) PropertyRow("Depends on") { Chips(detail, depends, onAction) }
    if (needed.isNotEmpty()) PropertyRow("Needed by") { Chips(detail, needed, onAction) }
    validation?.let { record ->
      val passed = record.passed
      val checks = record.result?.checks?.size ?: 0
      val word = when (passed) { true -> "passed"; false -> "failed"; null -> record.integrityProblem ?: "unknown" }
      PropertyRow("Validation") {
        Box(Modifier.size(6.dp).background(if (passed == false) colors.stFailed else if (passed == true) colors.stDone else colors.fgFaint, CircleShape))
        PropertyText(if (checks > 0) "$word · $checks checks" else word)
      }
    }
  }
}

/**
 * `.sb-chips`: the edges as SubtaskChips 4 apart on one line. Each chip keeps its full width (`flex: none`) and the strip clips
 * what does not fit (`overflow: hidden`); a Row would squeeze the last chips instead. A chip selects that subtask.
 */
@Composable
private fun RowScope.Chips(detail: Detail, edges: List<WorkDependency>, onAction: (DetailAction) -> Unit) {
  Layout(
    modifier = Modifier.weight(1f, fill = false).clipToBounds(),
    content = { ChipItems(detail, edges, onAction) },
  ) { measurables, constraints ->
    val gap = 4.dp.roundToPx()
    val placeables = measurables.map { it.measure(Constraints()) }
    val total = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
    val height = placeables.maxOfOrNull { it.height } ?: 0
    layout(total.coerceAtMost(constraints.maxWidth), height) {
      var x = 0
      placeables.forEach { chip ->
        chip.placeRelative(x, (height - chip.height) / 2)
        x += chip.width + gap
      }
    }
  }
}

@Composable
private fun ChipItems(detail: Detail, edges: List<WorkDependency>, onAction: (DetailAction) -> Unit) {
  edges.forEach { edge ->
    val target = detail.subtasks.firstOrNull { it.id == edge.subtaskId }
    val number = subtaskNumber(edge.subtaskId)
    val tooltip = listOfNotNull("Subtask $number", target?.name).joinToString(": ") + if (edge.optional) " (optional)" else ""
    SubtaskChip(number, subtaskStatusKind(target?.status.orEmpty()), edge.optional, tooltip) {
      onAction(DetailAction.SelectSubtask(SubtaskKey.Subtask(edge.subtaskId)))
    }
  }
}

/** `.sb-insp-sec`: the Review caption, one block per pass with the review phase's findings table, then the audit. */
@Composable
private fun SubtaskReview(detail: Detail, subtaskId: Int) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val results = detail.phaseResults.firstOrNull { it.subtaskId == subtaskId }
  val passes = results?.reviewPasses.orEmpty()
  val audit = results?.audit
  Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText("Review", style = type.tXs.copy(color = colors.fgFaint, fontWeight = FontWeight.Medium, lineHeight = 1.em))
    }
    if (passes.isEmpty()) {
      Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("No review yet", style = type.tSm.copy(color = colors.fgFaint))
      }
    }
    passes.forEachIndexed { index, pass -> PassBlock(pass, Modifier.padding(top = if (index > 0) 6.dp else 0.dp)) }
    audit?.verdict?.let { verdict ->
      Column(Modifier.padding(top = if (passes.isNotEmpty()) 6.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InspectorLine("Audit", listOf(verdict))
        audit.summary?.takeIf { it.isNotBlank() }?.let { ExpandableText(it, type.tSm.copy(color = colors.fgMuted, lineHeight = 1.45.em)) }
      }
    }
  }
}

/** A pass: its verdict and status tally, the lanes its review run applied, then its findings as the review phase shows them. */
@Composable
private fun PassBlock(pass: ReviewPass, modifier: Modifier) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    InspectorLine("Pass ${pass.passNumber}", listOf(reviewVerdictLabel(pass.verdict), reviewFindingsTally(pass.findings)))
    if (pass.lanes.isNotEmpty()) ReviewLanes(pass.lanes, pass.findings)
    if (pass.findings.isNotEmpty()) ReviewFindingsBox(pass.findings)
  }
}

/**
 * `.sb-insp-line`: a 22 px line, the lead in 12/500 `fg-2`, then "· part" in `fg-muted`, 4 apart, one line. The first part (the
 * verdict) takes the ellipsis; the short tail ("· 3 unresolved", tabular) stays whole. Two weighted parts would each be capped
 * at half the free width, cutting a long verdict next to empty space.
 */
@Composable
private fun InspectorLine(lead: String, parts: List<String>) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shown = parts.filter { it.isNotBlank() }
  Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicText(lead, style = type.tSmMedium.copy(color = colors.fg2), maxLines = 1, softWrap = false)
    shown.forEachIndexed { index, part ->
      val first = index == 0
      BasicText(
        "· $part",
        if (first) Modifier.weight(1f, fill = false) else Modifier,
        style = type.tSm.let { if (first) it else it.tabular() }.copy(color = colors.fgMuted),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

private fun PipelineStepState.status(): Pair<StatusKind, String> = when (this) {
  PipelineStepState.Done -> StatusKind.Completed to "Done"
  PipelineStepState.Current -> StatusKind.Active to "Running"
  PipelineStepState.Waiting -> StatusKind.Blocked to "Waiting"
  PipelineStepState.Failed -> StatusKind.Failed to "Failed"
  PipelineStepState.Pending -> StatusKind.Pending to "Pending"
}
