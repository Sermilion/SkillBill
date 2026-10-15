package dev.skillbill.tray.infrastructure

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import dev.skillbill.tray.core.domain.model.TrayStatus
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.infrastructure.macos.MacStatusItemBridge

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
    onFailure: () -> Unit,
    onDismiss: () -> Unit,
  ) {
    val currentPrimary = rememberUpdatedState(onPrimary)
    val currentMenuAction = rememberUpdatedState(onMenuAction)
    val currentFailure = rememberUpdatedState(onFailure)
    val currentDismiss = rememberUpdatedState(onDismiss)
    DisposableEffect(Unit) {
      MacStatusItemBridge.install(
        { currentPrimary.value() },
        { currentMenuAction.value(it) },
        { currentFailure.value() },
        { currentDismiss.value() },
      )
      onDispose { MacStatusItemBridge.dispose() }
    }
    SideEffect {
      MacStatusItemBridge.update(kind, running,
        goals.take(8).map { "${it.issueKey ?: it.currentStepLabel}: ${it.summary.take(50)}" },
        phases.take(8).map { "${it.phaseId ?: it.currentStepLabel}: ${it.summary.take(50)}" })
    }
  }
}
