package dev.skillbill.desktop.feature.work

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.skillbill.desktop.core.designsystem.component.SbMenu
import dev.skillbill.desktop.core.designsystem.component.SbMenuEntry
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.ui.input.ShellLayer

/**
 * A row's context menu, as the prototype's row menu lists it. Launch and Cancel stay disabled until the row's run options arrive
 * and while a launch reservation holds the item. Each entry runs and then closes the menu.
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
  val options = state.runOptions[item.id]
  val entries = buildList {
    add(entry("Open", LucideIcons.external, "↵") { onAction(WorkAction.Open(item)) })
    add(entry("Open terminal here", LucideIcons.terminal, enabled = item.repositoryRoot.isNotBlank()) { onOpenTerminal(item) })
    item.reason?.let { reason -> add(entry("Copy reason", LucideIcons.copy) { onCopyReason(reason) }) }
    if (item.kind == WorkKind.Goal) {
      add(SbMenuEntry.Separator)
      add(
        entry(if (item.pauseRequested) "Pause requested" else "Pause after subtask", LucideIcons.pause, "P", enabled = state.canPauseAfterSubtask(item)) {
          onAction(WorkAction.PauseAfterSubtask(item))
        },
      )
      add(entry("Pause", LucideIcons.stop, "⇧ P", danger = true, enabled = state.canPauseNow(item)) { onAction(WorkAction.PauseNow(item)) })
    }
    // Resume / Run again is listed only for resumable work (not active, not completed), as the prototype's row menu.
    if (item.lifecycle != Lifecycle.Active && item.lifecycle != Lifecycle.Terminal) {
      add(entry(options?.launchLabel ?: "Resume", LucideIcons.play, "R", enabled = state.canLaunch(item)) { onAction(WorkAction.Launch(item)) })
    }
    add(entry("Cancel", LucideIcons.x, danger = true, enabled = state.canCancel(item)) { onAction(WorkAction.Cancel(item)) })
  }
  ShellLayer(close)
  SbPopover(expanded = true, onDismissRequest = close) { SbMenu(entries) }
}
