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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular
import dev.skillbill.desktop.core.designsystem.component.Callout
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.ShortId
import dev.skillbill.desktop.core.designsystem.detail.PaneSection
import dev.skillbill.desktop.core.designsystem.detail.PaneStatus
import dev.skillbill.desktop.core.designsystem.detail.PropertyList
import dev.skillbill.desktop.core.designsystem.detail.PropertyRow
import dev.skillbill.desktop.core.designsystem.detail.PropertyText
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.list.SubtaskChip
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.ActivityResult
import dev.skillbill.desktop.core.domain.model.Detail
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

@Composable
internal fun SubtaskInspector(item: WorkItem, detail: Detail, subtask: WorkSubtask, phaseChoice: Map<PhaseChoiceKey, String>, activity: ActivityResult, inline: Boolean, renderedAt: kotlin.time.Instant, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (!inline) BasicText(subtask.name, style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg, lineHeight = 1.4.em))
    subtask.blockedReason?.takeIf { it.isNotBlank() }?.let { Hint(it, FeedbackTone.Warn, fold = true) }
    SubtaskProperties(detail, subtask, onAction)
    SubtaskPhasesSection(item, detail, subtask, phaseChoice, activity, renderedAt, onAction)
    SubtaskResults(detail, subtask)
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
      val word = when (passed) { true -> "passed"; false -> "failed"; null -> record.integrityProblem }
      if (word != null) PropertyRow("Validation") {
        Box(Modifier.size(6.dp).background(if (passed == false) colors.stFailed else if (passed == true) colors.stDone else colors.fgFaint, CircleShape))
        PropertyText(if (checks > 0) "$word · $checks ${if (checks == 1) "check" else "checks"}" else word)
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

/** `.sb-insp-sec`: what [subtaskResultsState] decided for the Review and Audit area. */
@Composable
private fun SubtaskResults(detail: Detail, subtask: WorkSubtask) {
  val results = detail.phaseResults.firstOrNull { it.subtaskId == subtask.id }
  val reviewInCard = results?.phaseCards?.any { it.phaseId == "review" } == true
  val state = subtaskResultsState(subtaskStatusKind(subtask.status), results, detail.degradedGoalStatusSource)
  Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    when (state) {
      is SubtaskResultsState.Unreadable -> UnreadableResults(state.source)
      SubtaskResultsState.Loading -> {
        PaneSection(title = "Review") {}
        PaneSection(title = "Audit") {}
      }
      is SubtaskResultsState.Shown -> {
        if (!reviewInCard) when (val review = state.review) {
          is SubtaskReviewState.Passes -> SubtaskReviewSection(review.passes, review.runningReviewPass)
          SubtaskReviewState.RunsAfterAudit -> ResultsPlaceholder("Review", "Runs after audit")
          SubtaskReviewState.Omitted -> Unit
        }
        SubtaskAuditSection(results, subtaskStatusKind(subtask.status), subtask.id)
      }
    }
  }
}

@Composable
private fun UnreadableResults(source: String) {
  val mono = SkillBillTheme.type.monoSm.fontFamily
  PaneSection(title = "Review") {
    Callout(
      FeedbackTone.Warn,
      body = buildAnnotatedString {
        append("Review and audit ran, but their results could not be read. The status read was degraded at ")
        withStyle(SpanStyle(fontFamily = mono)) { append(source) }
        append(".")
      },
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun ResultsPlaceholder(caption: String, line: String) {
  PaneSection(title = caption) {
    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(line, style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fgMuted))
    }
  }
}

/**
 * `.sb-insp-line`: a 22 px line, the lead in 12/500 `fg-2`, then "· part" in `fg-muted`, 4 apart, one line. The first part (the
 * verdict) takes the ellipsis; the short tail ("· 3 unresolved", tabular) stays whole. Two weighted parts would each be capped
 * at half the free width, cutting a long verdict next to empty space.
 */
@Composable
internal fun InspectorLine(lead: String, parts: List<String>) {
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
