package dev.skillbill.desktop.feature.work

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.ProgressRing
import dev.skillbill.desktop.core.designsystem.component.ProgressRingTone
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.dataColorFor
import dev.skillbill.desktop.core.designsystem.list.ListRow
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.status.statusKind
import kotlin.time.Instant

/** The id a row shows: the issue key for goals, the phase or operation id otherwise, as the prototype's `rowKey`. */
internal fun rowId(item: WorkItem): String =
  if (item.kind == WorkKind.Goal) item.issueKey ?: item.id else item.control.phaseId ?: item.issueKey ?: item.id

/** "No progress for 12m", worded and rounded as the prototype's duration labels; null while the board's time is unknown. */
internal fun stalledTooltip(stalledSince: Instant, now: Instant?): String? {
  if (now == null) return null
  val seconds = (now - stalledSince).inWholeSeconds.coerceAtLeast(0)
  val hours = seconds / 3_600
  val minutes = (seconds % 3_600) / 60
  val text = when {
    hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
    minutes > 0 -> "${minutes}m"
    else -> "${seconds}s"
  }
  return "No progress for $text"
}

/** One board row with the HANDOFF 9.1 facts, in the prototype's order. [narrow] drops the repository label, as `.hide-sm`. */
@Composable
internal fun WorkRow(item: WorkItem, focused: Boolean, renderedAt: Instant?, narrow: Boolean, onAction: (WorkAction) -> Unit, onMenuAt: (Offset) -> Unit = {}) {
  val kind = item.statusKind()
  val planning = kind == StatusKind.Planning
  val planned = item.planningProgress
  val finishedAt = item.finishedAt
  ListRow(
    title = item.title,
    onClick = {
      onAction(WorkAction.Focus(item))
      onAction(WorkAction.Open(item))
    },
    id = rowId(item),
    status = { StatusIcon(kind, (if (planning) planned else item.progress)?.let { it.done.toFloat() / it.total.coerceAtLeast(1) }) },
    summary = item.summary.ifBlank { null },
    meta = {
      item.stalledSince?.let { since -> SbLabel("Stalled", SbLabelStyle.Tint(SkillBillTheme.colors.stBlocked), tooltip = stalledTooltip(since, renderedAt)) }
      if (item.pauseRequested) SbLabel("Pause requested", SbLabelStyle.Dot(SkillBillTheme.colors.stPaused))
      if (item.lifecycle != Lifecycle.Terminal) {
        val step = if (planning && planned != null) "${planned.done}/${planned.total} plans saved"
        else item.stepLabel + item.phaseExecutionLabel?.let { " · $it" }.orEmpty()
        SbLabel(step, SbLabelStyle.Mono, tooltip = if (item.kind == WorkKind.Goal) "Current phase" else "Current step")
      }
      if (!narrow) SbLabel(item.repositoryName, SbLabelStyle.Dot(SkillBillTheme.colors.dataColorFor(item.repositoryName)))
      if (item.kind == WorkKind.Goal) {
        val progress = item.progress
        if (planning && planned != null) ProgressRing(planned.done, planned.total, ProgressRingTone.PlansSaved, tooltip = "Plans saved")
        else if (progress != null) ProgressRing(progress.done, progress.total, tooltip = "Subtasks complete")
      }
      item.agentId?.let { agent -> Avatar(agent, tooltip = listOfNotNull(agent, item.model, item.effort).joinToString(" · ")) }
    },
    time = if (finishedAt != null && renderedAt != null) finishedTimeText(finishedAt, renderedAt) else null,
    timeContent = if (finishedAt == null) {
      { SbClock(item.activeDurationMs, item.activeDurationAsOf, live = true, showSeconds = item.kind != WorkKind.Goal) }
    } else null,
    focused = focused,
    faded = finishedAt != null,
    narrow = narrow,
    onContextMenu = { at: Offset ->
      onMenuAt(at)
      onAction(WorkAction.OpenMenu(item))
    },
  )
}
