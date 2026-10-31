package dev.skillbill.desktop.feature.tray

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.ProgressRing
import dev.skillbill.desktop.core.designsystem.component.ProgressRingTone
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbClock
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.ViewTab
import dev.skillbill.desktop.core.designsystem.component.ViewTabs
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.list.ListRow
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.settings.TrayTab
import dev.skillbill.desktop.core.designsystem.tray.TrayPopover
import dev.skillbill.desktop.core.designsystem.tray.TrayStat as PopoverStat
import dev.skillbill.desktop.core.designsystem.tray.TrayStatTone as PopoverTone
import dev.skillbill.desktop.core.ui.status.statusKind
import dev.skillbill.desktop.core.ui.feedback.StaleIndicator
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun TrayPopoverContent(
  state: TrayUiState,
  onAction: (TrayAction) -> Unit,
  onOpen: () -> Unit,
  onSettings: () -> Unit,
  onQuit: () -> Unit,
  onDismiss: () -> Unit,
) {
  val loaded = state.board as? TrayBoard.Loaded
  TrayPopover(
    stats = loaded?.let { listOf(
      // The same section sizes the sidebar and Work show, so the numbers agree everywhere.
      PopoverStat(it.running.toString(), "Running", PopoverTone.Accent),
      PopoverStat(it.needsYou.toString(), "Needs you", PopoverTone.Attention),
      PopoverStat(it.finishedToday.toString(), "Finished today", PopoverTone.Neutral),
    ) } ?: emptyList(),
    tabs = {
      ViewTabs(
        tabs = listOf(
          ViewTab(TrayTab.Goals, "Goals", count = loaded?.goals?.size ?: 0, attention = loaded?.attention(TrayTab.Goals) == true),
          ViewTab(TrayTab.Phases, "Phases", count = loaded?.livePhases?.size ?: 0, attention = loaded?.attention(TrayTab.Phases) == true),
          ViewTab(TrayTab.Operations, "Operations", count = loaded?.liveOperations?.size ?: 0, attention = loaded?.attention(TrayTab.Operations) == true),
        ),
        selected = state.tab,
        onSelect = { onAction(TrayAction.ChooseTab(it)) },
      )
    },
    footer = {
      SbButton("Open Skill Bill", onClick = onOpen, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SbIconButton(LucideIcons.refresh, "Refresh", onClick = { onAction(TrayAction.Refresh) }, size = SbControlSize.Small)
        SbIconButton(LucideIcons.sliders, "Settings", onClick = onSettings, size = SbControlSize.Small)
        SbButton("Quit", onClick = onQuit, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
      }
    },
    headerEnd = {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.stale) StaleIndicator()
        SbIconButton(LucideIcons.x, "Close tray popover", onClick = onDismiss, size = SbControlSize.Small)
      }
    },
  ) {
    when (val board = state.board) {
      TrayBoard.Loading -> Message("Loading your work")
      TrayBoard.Failed -> Message("Couldn't load your work. Check the database path in Settings, then try refreshing.")
      is TrayBoard.Loaded -> when (state.tab) {
        TrayTab.Goals -> workRows(board.goals, "No live goals", onAction)
        TrayTab.Phases -> {
          workRows(board.livePhases, "No live phases", onAction)
          finishedRows(board.finishedPhases, onAction)
        }
        TrayTab.Operations -> {
          workRows(board.liveOperations, "No live operations", onAction)
          finishedRows(board.finishedOperations, onAction)
        }
      }
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.workRows(items: List<WorkItem>, empty: String, onAction: (TrayAction) -> Unit) {
  if (items.isEmpty()) Message(empty) else items.forEach { TrayRow(it, false) { onAction(TrayAction.Select(it)) } }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.finishedRows(items: List<WorkItem>, onAction: (TrayAction) -> Unit) {
  if (items.isNotEmpty()) {
    // A menu head (`.sb-menu-head`) with the tray's 8/12/4 padding.
    BasicText(
      "Finished in the last hour",
      Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
      // 500 at the xs size on a 1.0 line, as `.sb-menu-head`.
      style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, lineHeight = 1.em),
      maxLines = 1,
    )
    items.forEach { TrayRow(it, true) { onAction(TrayAction.Select(it)) } }
  }
}

@Composable
private fun Message(value: String) {
  BasicText(value, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
}

@Composable
private fun TrayRow(item: WorkItem, finished: Boolean, onClick: () -> Unit) {
  val kind = item.statusKind()
  val planning = kind == StatusKind.Planning
  val progress = if (planning) item.planningProgress else item.progress
  ListRow(
    title = item.repositoryName,
    onClick = onClick,
    id = if (item.kind == WorkKind.Goal) item.issueKey ?: item.id else item.control.phaseId ?: item.issueKey ?: item.id,
    status = { StatusIcon(kind, progress?.let { it.done.toFloat() / it.total.coerceAtLeast(1) }) },
    summary = when {
      item.pauseRequested -> "Pause requested"
      item.lifecycle == Lifecycle.Active -> trayStepText(item, planning)
      else -> lifecycleWord(item.lifecycle)
    },
    meta = {
      if (item.kind == WorkKind.Goal && progress != null) ProgressRing(progress.done, progress.total, if (planning) ProgressRingTone.PlansSaved else ProgressRingTone.Subtasks, showText = false)
    },
    time = item.finishedAt?.let { trayFinishedTimeText(it, Clock.System.now()) },
    timeContent = if (item.finishedAt == null) ({ SbClock(item.activeDurationMs, item.activeDurationAsOf, live = true, showSeconds = item.kind != WorkKind.Goal) }) else null,
    compact = true,
    faded = finished,
  )
}

/** The step a live row shows, as the Work board's step label: "n/m plans saved" while planning, else the step and its execution. */
private fun trayStepText(item: WorkItem, planning: Boolean): String {
  val planned = item.planningProgress
  return if (planning && planned != null) "${planned.done}/${planned.total} plans saved"
  else item.stepLabel + item.phaseExecutionLabel?.let { " · $it" }.orEmpty()
}

/** The lifecycle's display word in lower case, as the prototype's `LIFE` labels: Terminal reads "completed". */
private fun lifecycleWord(lifecycle: Lifecycle): String = when (lifecycle) {
  Lifecycle.Active -> "active"
  Lifecycle.Paused -> "paused"
  Lifecycle.Blocked -> "blocked"
  Lifecycle.Failed -> "failed"
  Lifecycle.Terminal -> "completed"
  Lifecycle.Canceled -> "canceled"
  Lifecycle.Idle -> "idle"
}

internal expect fun trayFinishedTimeText(finishedAt: Instant, now: Instant): String
