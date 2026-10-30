package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.FindingRow
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.ProgressRing
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.detail.PhasePipeline
import dev.skillbill.desktop.core.designsystem.detail.section
import dev.skillbill.desktop.core.designsystem.list.SubtaskRow
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.status.statusKind
import dev.skillbill.desktop.core.ui.status.subtaskStatusKind

/**
 * The next page of [list], after that list's last loaded row. A `LazyColumn` composes this item only near the viewport, so the
 * request runs when a short list's end is on screen or when the user scrolls to it; the view model reads each anchor once.
 * [inBox] draws it as a middle segment of the box its list sits in, for a box that continues after it.
 */
internal fun LazyListScope.pageFooter(list: DetailList, state: DetailUiState, onAction: (DetailAction) -> Unit, inBox: Boolean = false) {
  val loaded = state.sections as? SectionsUi.Loaded ?: return
  if (list !in loaded.moreLists) return
  item(key = "more-${list.name}") {
    val anchor = loaded.listAnchors[list]
    val load = state.listLoads[list]
    val frame = if (inBox) Modifier.fillMaxWidth().sbBoxSegment(1, 3).bottomRule(SkillBillTheme.colors.borderSubtle).padding(start = 12.dp, end = 12.dp, bottom = 1.dp) else Modifier
    Column(frame.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (load is ListLoad.Failed) {
        Hint(readProblemText(load.problem), FeedbackTone.Warn)
        SbButton("Retry page", { onAction(DetailAction.LoadMore(list)) }, size = SbControlSize.Small)
      } else {
        LaunchedEffect(list, anchor) { onAction(DetailAction.LoadMore(list)) }
        Hint("Loading more")
      }
    }
  }
}

/** The `.sb-sub` bottom rule for a footer that sits between subtask rows. */
private fun Modifier.bottomRule(color: Color) = drawBehind {
  val stroke = 1.dp.toPx()
  drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
}

/** A [FindingRow] as one row of a `.sb-box`: side borders, the box's top and bottom on the ends, a rule between rows. */
@Composable
internal fun BoxedFindingRow(
  index: Int,
  count: Int,
  title: String,
  severity: String? = null,
  ref: String? = null,
  location: String? = null,
  meta: String? = null,
  state: String? = null,
  stateColor: Color? = null,
) {
  Box(Modifier.fillMaxWidth().sbBoxSegment(index, count)) {
    FindingRow(title, severity, ref, location, meta, state, stateColor, showDivider = index > 0)
  }
}

/** HANDOFF 9.2 item 9: the subtasks in one box, then the Goal finish row. Goals only. */
internal fun LazyListScope.subtaskSection(item: WorkItem, detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (item.kind != WorkKind.Goal) return
  if (item.statusKind() == StatusKind.Planning) {
    val plans = item.planningProgress
    // The design's count reads "2/5 plans saved" and the header has no ring while planning.
    section(
      "subtasks", "Subtasks",
      count = plans?.let { "${it.done}/${it.total} plans saved" },
    ) {
      item(key = "subtasks-planning") { Hint("Subtasks appear here once their plans are saved.") }
    }
    return
  }
  val finished = finishedAt(item) != null
  val count = detail.subtasks.size + 1
  val progress = item.progress
  section(
    "subtasks", "Subtasks",
    count = progress?.let { "${it.done}/${it.total}" } ?: detail.subtasks.size.toString(),
    actions = if (progress == null) null else { { ProgressRing(progress.done, progress.total, showText = false) } },
  ) {
    itemsIndexed(detail.subtasks, key = { _, subtask -> "subtask-${subtask.id}" }) { index, subtask ->
      val colors = SkillBillTheme.colors
      val current = !finished && subtask.id.toString() == item.currentSubtaskId
      val status = subtaskStatusKind(subtask.status)
      val reason = subtask.blockedReason
      SubtaskRow(
        number = subtask.id.toString().padStart(2, '0'),
        status = status,
        progress = null,
        title = subtask.name,
        modifier = Modifier.sbBoxSegment(index, count),
        current = current,
        meta = {
          // `.mono.t-faint` at `--t-2xs`: plain text, not a pill.
          subtask.commitSha?.let { sha ->
            SbTooltip(sha) { BasicText(sha.take(7), style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint, fontWeight = FontWeight.Normal), maxLines = 1) }
          }
          // The design ticks the current subtask's clock only while the run is live.
          if (current && item.running) {
            CompositionLocalProvider(LocalContentColor provides colors.fg) {
              SbClock(detail.subtaskActiveDurationMs, detail.subtaskActiveDurationAsOf, live = true, showSeconds = true, style = SkillBillTheme.type.tBase)
            }
          }
          if (status == StatusKind.CompletedNoChange) SbLabel("no change")
        },
        body = if (reason == null) null else { { Hint(reason, FeedbackTone.Warn) } },
      )
    }
    pageFooter(DetailList.Subtasks, state, onAction, inBox = true)
    item(key = "goal-finish") {
      SubtaskRow(
        number = "··",
        status = if (finished) StatusKind.Completed else StatusKind.Pending,
        progress = null,
        title = "Goal finish",
        modifier = Modifier.sbBoxSegment(count - 1, count),
        divider = false,
        body = { PhasePipeline(goalFinishSteps(item, detail)) },
      )
    }
  }
}

/** HANDOFF 9.2 item 10, per screens.js: goals past planning, and any other run with more than one step. */
internal fun LazyListScope.pipelineSection(item: WorkItem, detail: Detail) {
  val show = when (item.kind) {
    WorkKind.Goal -> item.statusKind() != StatusKind.Planning
    WorkKind.Operation, WorkKind.Phase, null -> detail.stepPipeline.size > 1
  }
  if (!show || detail.stepPipeline.isEmpty()) return
  section("pipeline", "Phase pipeline") {
    item(key = "pipeline-steps") { PhasePipeline(pipelineSteps(item, detail, detail.stepPipeline)) }
  }
}

/** HANDOFF 9.2 item 11: one boxed row per edge; the design's header carries no count. */
internal fun LazyListScope.dependencySection(detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (detail.dependencies.isEmpty()) return
  val count = detail.dependencies.size
  section("dependencies", "Dependencies") {
    itemsIndexed(detail.dependencies, key = { index, dependency -> "dependency-${dependency.subtaskId}-${dependency.dependency.subtaskId}-$index" }) { index, entry ->
      BoxedFindingRow(
        index, count,
        title = "Subtask ${entry.subtaskId} waits on subtask ${entry.dependency.subtaskId}",
        meta = if (entry.dependency.optional) "optional" else "required",
      )
    }
    pageFooter(DetailList.Dependencies, state, onAction)
  }
}
