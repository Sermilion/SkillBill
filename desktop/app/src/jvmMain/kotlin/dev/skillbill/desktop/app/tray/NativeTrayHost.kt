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
    imagePng: ByteArray,
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
      val active = java.util.concurrent.atomic.AtomicBoolean(true)
      MacStatusItemBridge.install(
        imagePng,
        { if (active.get()) currentPrimary.value() },
        { if (active.get()) currentMenuAction.value(it) },
        { if (active.get()) currentAvailable.value() },
        { if (active.get()) currentFailure.value() },
        { if (active.get()) currentDismiss.value() },
      )
      onDispose {
        active.set(false)
        try {
          MacStatusItemBridge.dispose()
        } finally {
          currentFailure.value()
        }
      }
    }
    SideEffect {
      try {
        MacStatusItemBridge.update(imagePng, kind, running,
          goals.take(8).map { "${it.issueKey ?: it.stepLabel}: ${it.summary.take(50)}" },
          phases.take(8).map { "${it.control.phaseId ?: it.stepLabel} · ${it.outcomeLabel()}: ${it.summary.take(50)}" })
      } catch (_: Exception) {
        try {
          MacStatusItemBridge.dispose()
        } finally {
          currentFailure.value()
        }
      } catch (_: LinkageError) {
        try {
          MacStatusItemBridge.dispose()
        } finally {
          currentFailure.value()
        }
      }
    }
  }
}
