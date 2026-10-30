package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.detail.PhasePipeline
import dev.skillbill.desktop.core.designsystem.list.SubtaskList
import dev.skillbill.desktop.core.designsystem.list.SubtaskRow
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.feedback.readProblemText
import dev.skillbill.desktop.core.ui.status.statusKind
import dev.skillbill.desktop.core.ui.status.subtaskStatusKind
import dev.skillbill.desktop.core.designsystem.component.ShortId

/**
 * The next page of [list], after that list's last loaded row. A `LazyColumn` composes this item only near the viewport, so the
 * request runs when a short list's end is on screen or when the user scrolls to it; the view model reads each anchor once.
 * [inBox] draws it as a middle segment of the box its list sits in, for a box that continues after it.
 */
internal fun LazyListScope.pageFooter(list: DetailList, state: DetailUiState, onAction: (DetailAction) -> Unit, inBox: Boolean = false) {
  val loaded = state.sections as? SectionsUi.Loaded ?: return
  if (list !in loaded.moreLists) return
  item(key = "more-${list.name}") {
    val frame = if (inBox) Modifier.fillMaxWidth().sbBoxSegment(1, 3).bottomRule(SkillBillTheme.colors.borderSubtle).padding(start = 12.dp, end = 12.dp, bottom = 1.dp) else Modifier
    PageFooter(list, state, onAction, frame)
  }
}

/** The page request and its state for [list]: "Loading more" while it reads, or the problem and Retry page. */
@Composable
private fun PageFooter(list: DetailList, state: DetailUiState, onAction: (DetailAction) -> Unit, modifier: Modifier = Modifier) {
  val loaded = state.sections as? SectionsUi.Loaded ?: return
  if (list !in loaded.moreLists) return
  val anchor = loaded.listAnchors[list]
  val load = state.listLoads[list]
  Column(modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (load is ListLoad.Failed) {
      Hint(readProblemText(load.problem), FeedbackTone.Warn)
      SbButton("Retry page", { onAction(DetailAction.LoadMore(list)) }, size = SbControlSize.Small)
    } else {
      LaunchedEffect(list, anchor) { onAction(DetailAction.LoadMore(list)) }
      Hint("Loading more")
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
  refContent: (@Composable () -> Unit)? = null,
) {
  Box(Modifier.fillMaxWidth().sbBoxSegment(index, count)) {
    FindingRow(title, severity, ref, location, meta, state, stateColor, showDivider = index > 0, refContent = refContent)
  }
}

/**
 * HANDOFF 9.2 item 9: the Subtasks index, one focusable box with the subtasks and a final Goal finish row. A row selects its
 * subtask for the side peek. The dependency and validation lists the inspector reads page in behind it. Goals only.
 */
internal fun DetailSections.subtaskSection(item: WorkItem, detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
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
  // screens.js `hasSubtasks`: a goal past planning with no subtasks has no Subtasks section, unless its first page is still to come.
  val loaded = state.sections as? SectionsUi.Loaded
  if (detail.subtasks.isEmpty() && loaded?.moreLists?.contains(DetailList.Subtasks) != true) return
  val progress = item.progress
  section(
    "subtasks", "Subtasks",
    count = progress?.let { "${it.done}/${it.total}" } ?: detail.subtasks.size.toString(),
    actions = if (progress == null) null else { { ProgressRing(progress.done, progress.total, showText = false) } },
  ) {
    item(key = "subtask-index") { SubtaskIndex(item, detail, state, onAction) }
  }
}

@Composable
private fun SubtaskIndex(item: WorkItem, detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val shellInput = LocalShellInput.current
  DisposableEffect(shellInput) { onDispose { shellInput.setFocusSource(ShellInput.SUBTASK_LIST_SOURCE, false) } }
  val selected = selectedSubtask(item, detail, state.subtaskChoice.key)
  val current = currentSubtask(item, detail)
  val reveal = remember(item.id) { mutableMapOf<SubtaskKey, BringIntoViewRequester>() }
  var revealed by remember(item.id) { mutableStateOf(state.subtaskChoice) }
  fun selectVisibleRow(key: SubtaskKey) {
    revealed = SubtaskChoice(key)
    onAction(DetailAction.SelectSubtask(key))
  }
  LaunchedEffect(state.subtaskChoice) {
    if (state.subtaskChoice == revealed) return@LaunchedEffect
    revealed = state.subtaskChoice
    withFrameNanos { }
    selectedSubtask(item, detail, state.subtaskChoice.key)?.let { reveal[it] }?.bringIntoView()
  }
  fun revealModifier(key: SubtaskKey) = Modifier.bringIntoViewRequester(reveal.getOrPut(key) { BringIntoViewRequester() })
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SubtaskList(
      label = "Subtasks of ${rowKey(item)}",
      onMove = { onAction(DetailAction.MoveSubtaskSelection(it)) },
      onOpen = { onAction(DetailAction.OpenSelectedSubtaskSpec) },
      onFocusChange = { focused ->
        shellInput.setFocusSource(ShellInput.SUBTASK_LIST_SOURCE, focused)
        if (focused && state.focusSubtaskList) onAction(DetailAction.SubtaskListFocused)
      },
      requestFocus = state.focusSubtaskList,
    ) {
      detail.subtasks.forEach { subtask ->
        val key = SubtaskKey.Subtask(subtask.id)
        val isCurrent = subtask.id == current?.id
        val status = subtaskStatusKind(subtask.status)
        val isSelected = key == selected
        SubtaskRow(
          modifier = revealModifier(key),
          number = subtaskNumber(subtask.id),
          status = status,
          progress = null,
          title = subtask.name,
          current = isCurrent,
          selected = isSelected,
          onClick = { selectVisibleRow(key) },
          meta = {
            subtask.commitSha?.let { sha -> ShortId(sha, { onAction(DetailAction.Copy(it)) }, label = "commit") }
            // The design ticks the current subtask's clock only while the run is live.
            if (isCurrent && item.running) {
              CompositionLocalProvider(LocalContentColor provides colors.fg) {
                SbClock(detail.subtaskActiveDurationMs, detail.subtaskActiveDurationAsOf, live = true, showSeconds = true, style = SkillBillTheme.type.tBase)
              }
            }
            if (status == StatusKind.CompletedNoChange) SbLabel("no change")
          },
        )
      }
      PageFooter(DetailList.Subtasks, state, onAction, Modifier.fillMaxWidth().bottomRule(colors.borderSubtle).padding(start = 12.dp, end = 12.dp, bottom = 1.dp))
      SubtaskRow(
        modifier = revealModifier(SubtaskKey.GoalFinish),
        number = "··",
        status = goalFinishStatus(item),
        progress = null,
        title = "Goal finish",
        selected = selected == SubtaskKey.GoalFinish,
        onClick = { selectVisibleRow(SubtaskKey.GoalFinish) },
        divider = false,
        body = {
          PhasePipeline(goalFinishSteps(item, detail))
        },
      )
    }
    BackgroundPages(state, onAction)
  }
}

/** The dependency and validation pages the inspector reads load as soon as the index shows; only a failure draws anything. */
@Composable
private fun BackgroundPages(state: DetailUiState, onAction: (DetailAction) -> Unit) {
  val loaded = state.sections as? SectionsUi.Loaded ?: return
  listOf(DetailList.Dependencies, DetailList.Validation).filter { it in loaded.moreLists }.forEach { list ->
    val anchor = loaded.listAnchors[list]
    when (val load = state.listLoads[list]) {
      is ListLoad.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hint(readProblemText(load.problem), FeedbackTone.Warn)
        SbButton("Retry page", { onAction(DetailAction.LoadMore(list)) }, size = SbControlSize.Small)
      }
      else -> LaunchedEffect(list, anchor) { onAction(DetailAction.LoadMore(list)) }
    }
  }
}

/** HANDOFF 9.2 item 10, per screens.js: goals past planning, and any other run with more than one step. */
internal fun DetailSections.pipelineSection(item: WorkItem, detail: Detail) {
  val show = when (item.kind) {
    WorkKind.Goal -> item.statusKind() != StatusKind.Planning
    WorkKind.Operation, WorkKind.Phase, null -> detail.stepPipeline.size > 1
  }
  if (!show || detail.stepPipeline.isEmpty()) return
  section("pipeline", "Phase pipeline") {
    item(key = "pipeline-steps") { PhasePipeline(pipelineSteps(item, detail, detail.stepPipeline)) }
  }
}

/** Standalone phases and operations: one boxed row per edge; goals show their edges in the subtask inspector. */
internal fun DetailSections.dependencySection(item: WorkItem, detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (item.kind == WorkKind.Goal || detail.dependencies.isEmpty()) return
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
