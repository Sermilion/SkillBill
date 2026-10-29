package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.FindingRow
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.ProgressRing
import dev.skillbill.desktop.core.designsystem.component.ProgressRingTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.composite.boxSegment
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
 */
internal fun LazyListScope.pageFooter(list: DetailList, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  val loaded = state.sections as? SectionsUi.Loaded ?: return
  if (list !in loaded.moreLists) return
  item(key = "more-${list.name}") {
    val anchor = loaded.listAnchors[list]
    val load = state.listLoads[list]
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/** HANDOFF 9.2 item 9: the subtasks, then the Goal finish row. Goals only. */
internal fun LazyListScope.subtaskSection(item: WorkItem, detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (item.kind != WorkKind.Goal) return
  if (item.statusKind() == StatusKind.Planning) {
    val plans = item.planningProgress
    section("subtasks", "Subtasks", actions = if (plans == null) null else { { ProgressRing(plans.done, plans.total, ProgressRingTone.PlansSaved) } }) {
      item(key = "subtasks-planning") { Hint("Subtasks appear here once their plans are saved.") }
    }
    return
  }
  val finished = finishedAt(item) != null
  val count = detail.subtasks.size + 1
  val progress = item.progress
  section("subtasks", "Subtasks", count = detail.subtasks.size, actions = if (progress == null) null else { { ProgressRing(progress.done, progress.total, showText = false) } }) {
    itemsIndexed(detail.subtasks, key = { _, subtask -> "subtask-${subtask.id}" }) { index, subtask ->
      val current = !finished && subtask.id.toString() == item.currentSubtaskId
      val status = subtaskStatusKind(subtask.status)
      val reason = subtask.blockedReason
      SubtaskRow(
        number = subtask.id.toString().padStart(2, '0'),
        status = status,
        progress = null,
        title = subtask.name,
        modifier = Modifier.boxSegment(index, count),
        current = current,
        meta = {
          subtask.commitSha?.let { sha -> SbTooltip(sha) { SbLabel(sha.take(7), SbLabelStyle.Mono) } }
          if (current) SbClock(detail.subtaskActiveDurationMs, detail.subtaskActiveDurationAsOf, live = item.running, showSeconds = true)
          if (status == StatusKind.CompletedNoChange) SbLabel("no change")
        },
        body = if (reason == null) null else { { Hint(reason, FeedbackTone.Warn) } },
      )
    }
    pageFooter(DetailList.Subtasks, state, onAction)
    item(key = "goal-finish") {
      SubtaskRow(
        number = "··",
        status = if (finished) StatusKind.Completed else StatusKind.Pending,
        progress = null,
        title = "Goal finish",
        modifier = Modifier.boxSegment(count - 1, count),
        divider = false,
        body = { PhasePipeline(goalFinishSteps(item, detail)) },
      )
    }
  }
}

/** HANDOFF 9.2 item 10: goals past planning and standalone phases with more than one step. Operations show none. */
internal fun LazyListScope.pipelineSection(item: WorkItem, detail: Detail) {
  val show = when (item.kind) {
    WorkKind.Goal -> item.statusKind() != StatusKind.Planning
    WorkKind.Operation -> false
    WorkKind.Phase, null -> detail.stepPipeline.size > 1
  }
  if (!show || detail.stepPipeline.isEmpty()) return
  section("pipeline", "Phase pipeline") {
    item(key = "pipeline-steps") { PhasePipeline(pipelineSteps(item, detail, detail.stepPipeline)) }
  }
}

/** HANDOFF 9.2 item 11. */
internal fun LazyListScope.dependencySection(detail: Detail, state: DetailUiState, onAction: (DetailAction) -> Unit) {
  if (detail.dependencies.isEmpty()) return
  section("dependencies", "Dependencies", count = detail.dependencies.size) {
    itemsIndexed(detail.dependencies, key = { index, dependency -> "dependency-${dependency.subtaskId}-${dependency.dependency.subtaskId}-$index" }) { _, entry ->
      FindingRow(
        title = "Subtask ${entry.subtaskId} waits on subtask ${entry.dependency.subtaskId}",
        meta = if (entry.dependency.optional) "optional" else "required",
      )
    }
    pageFooter(DetailList.Dependencies, state, onAction)
  }
}
