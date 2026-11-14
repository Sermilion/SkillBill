package dev.skillbill.desktop.feature.work

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.input.ShellLayer

internal enum class WorkRowMenuAction { Open, OpenTerminal, CopyReason, ExportWork, PauseAfterSubtask, PauseNow, Launch, Finalize, Cancel }

internal data class WorkRowMenuEntry(val action: WorkRowMenuAction, val label: String, val enabled: Boolean)

/**
 * A row's context menu, as the prototype's row menu lists it. Launch and Finalize appear from runtime options; Cancel stays
 * disabled until those options arrive and while a launch reservation holds the item. Each entry runs and then closes the menu.
 */
@Composable
internal fun WorkRowMenu(
  state: WorkUiState,
  item: WorkItem,
  onAction: (WorkAction) -> Unit,
  onOpenTerminal: (WorkItem) -> Unit,
  onCopyReason: (String) -> Unit,
) {
  val close = { onAction(WorkAction.CloseMenu) }
  fun entry(label: String, icon: ImageVector, shortcut: String? = null, danger: Boolean = false, enabled: Boolean = true, run: () -> Unit) =
    SbMenuEntry.Item(label, { run(); close() }, icon = icon, shortcut = shortcut, danger = danger, enabled = enabled)
  val entries = buildList {
    workRowMenuEntries(state, item).forEach { spec ->
      when (spec.action) {
        WorkRowMenuAction.Open -> add(entry(spec.label, LucideIcons.external, "↵") { onAction(WorkAction.Open(item)) })
        WorkRowMenuAction.OpenTerminal ->
          add(entry(spec.label, LucideIcons.terminal, enabled = spec.enabled) { onOpenTerminal(item) })
        WorkRowMenuAction.CopyReason ->
          add(entry(spec.label, LucideIcons.copy, enabled = spec.enabled) { item.reason?.let(onCopyReason) })
        WorkRowMenuAction.ExportWork -> {
          add(SbMenuEntry.Separator)
          add(entry(spec.label, LucideIcons.file, enabled = spec.enabled) { onAction(WorkAction.RequestExport(item)) })
        }
        WorkRowMenuAction.PauseAfterSubtask ->
          add(entry(spec.label, LucideIcons.pause, "P", enabled = spec.enabled) { onAction(WorkAction.PauseAfterSubtask(item)) })
        WorkRowMenuAction.PauseNow ->
          add(entry(spec.label, LucideIcons.stop, "⇧ P", danger = true, enabled = spec.enabled) { onAction(WorkAction.PauseNow(item)) })
        WorkRowMenuAction.Launch ->
          add(entry(spec.label, LucideIcons.play, "R", enabled = spec.enabled) { onAction(WorkAction.Launch(item)) })
        WorkRowMenuAction.Finalize ->
          add(entry(spec.label, LucideIcons.checkCircle, enabled = spec.enabled) { onAction(WorkAction.Finalize(item)) })
        WorkRowMenuAction.Cancel ->
          add(entry(spec.label, LucideIcons.x, danger = true, enabled = spec.enabled) { onAction(WorkAction.Cancel(item)) })
      }
    }
  }
  ShellLayer(close)
  // The anchor is the click point; -4 cancels the popover's anchor gap so the menu's corner sits on the pointer.
  SbPopover(expanded = true, onDismissRequest = close, offset = DpOffset(0.dp, (-4).dp)) { SbMenu(entries) }
}

internal fun workRowMenuEntries(state: WorkUiState, item: WorkItem): List<WorkRowMenuEntry> = buildList {
  add(WorkRowMenuEntry(WorkRowMenuAction.Open, "Open", true))
  add(WorkRowMenuEntry(WorkRowMenuAction.OpenTerminal, "Open terminal here", item.repositoryRoot.isNotBlank()))
  if (item.reason != null) add(WorkRowMenuEntry(WorkRowMenuAction.CopyReason, "Copy reason", true))
  if (item.kind == WorkKind.Goal) {
    add(WorkRowMenuEntry(WorkRowMenuAction.ExportWork, "Export work", true))
    add(
      WorkRowMenuEntry(
        WorkRowMenuAction.PauseAfterSubtask,
        if (item.pauseRequested) "Pause requested" else "Pause after subtask",
        state.canPauseAfterSubtask(item),
      ),
    )
    add(WorkRowMenuEntry(WorkRowMenuAction.PauseNow, "Pause", state.canPauseNow(item)))
  }
  if (state.canLaunch(item)) {
    add(WorkRowMenuEntry(WorkRowMenuAction.Launch, state.runOptions[item.id]?.launchLabel ?: "Resume", true))
  }
  if (state.canFinalize(item)) add(WorkRowMenuEntry(WorkRowMenuAction.Finalize, "Finalize", true))
  add(WorkRowMenuEntry(WorkRowMenuAction.Cancel, "Cancel", state.canCancel(item)))
}
