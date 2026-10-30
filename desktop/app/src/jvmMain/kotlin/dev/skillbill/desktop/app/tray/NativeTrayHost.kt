package dev.skillbill.desktop.app.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.skillbill.desktop.app.tray.macos.MacStatusItemBridge
import dev.skillbill.desktop.core.domain.model.TrayStatus
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.outcomeLabel

data class NativeTrayState(val kind: TrayStatus, val running: Int)

object NativeTrayHost {
  fun state(kind: TrayStatus, running: Int) = NativeTrayState(kind, running.coerceAtLeast(0))
  fun showsOverlay(platform: String): Boolean = !platform.contains("mac", true)

  @Composable
  fun MacStatusItem(
    kind: TrayStatus,
    running: Int,
    goals: List<WorkItem>,
    phases: List<WorkItem>,
    onPrimary: () -> Unit,
    onMenuAction: (Int) -> Unit,
    onAvailable: () -> Unit,
    onFailure: () -> Unit,
    onDismiss: () -> Unit,
  ) {
    val currentPrimary = rememberUpdatedState(onPrimary)
    val currentMenuAction = rememberUpdatedState(onMenuAction)
    val currentAvailable = rememberUpdatedState(onAvailable)
    val currentFailure = rememberUpdatedState(onFailure)
    val currentDismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
      MacStatusItemBridge.install(
        { currentPrimary.value() },
        { currentMenuAction.value(it) },
        { currentAvailable.value() },
        { currentFailure.value() },
        { currentDismiss.value() },
      )
      onDispose { MacStatusItemBridge.dispose() }
    }
    SideEffect {
      MacStatusItemBridge.update(kind, running,
        goals.take(8).map { "${it.issueKey ?: it.stepLabel}: ${it.summary.take(50)}" },
        phases.take(8).map { "${it.control.phaseId ?: it.stepLabel} · ${it.outcomeLabel()}: ${it.summary.take(50)}" })
    }
  }
}
