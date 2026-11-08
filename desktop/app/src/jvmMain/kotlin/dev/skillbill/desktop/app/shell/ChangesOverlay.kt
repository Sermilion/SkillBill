package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelStore
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.ui.changes.ChangesSource
import dev.skillbill.desktop.core.ui.changes.ChangesWindowEntry
import dev.skillbill.desktop.feature.changes.ChangesScreen
import dev.skillbill.desktop.feature.changes.ChangesWindowFrame
import dev.skillbill.desktop.feature.changes.ChangesWindowKeys
import dev.skillbill.desktop.feature.changes.ChangesWindowViewModel

/** The share of the main window's width and height the Changes panel takes; the shell stays visible around it. */
private const val PANEL_SHARE = 0.94f

/** The Changes window: a fixed panel over the shell, styled as a window. A click on the scrim, the close button or Esc closes it. */
@Composable
fun ChangesOverlay(
  entry: ChangesWindowEntry,
  keys: ChangesWindowKeys,
  createViewModel: (ChangesSource, ChangeScope, String?) -> ChangesWindowViewModel,
  onClose: () -> Unit,
) {
  val store = remember { ViewModelStore() }
  DisposableEffect(store) { onDispose { store.clear() } }
  val viewModel = remember { createViewModel(entry.source, entry.scope, entry.selectedPath).also { store.put("changes", it) } }
  val state by viewModel.state.collectAsState()
  LaunchedEffect(entry.focusRequest) { if (entry.focusRequest > 0) viewModel.reopen(entry.source, entry.scope, entry.selectedPath) }
  Dialog(
    onClose,
    DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = true, usePlatformDefaultWidth = false, scrimColor = SkillBillTheme.colors.scrim),
  ) {
    BoxWithConstraints(
      Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        Modifier.size(maxWidth * PANEL_SHARE, maxHeight * PANEL_SHARE)
          .clickable(remember { MutableInteractionSource() }, indication = null) { },
      ) {
        ChangesWindowFrame(state.title, closeAtStart = closeButtonAtStart(), onClose) { ChangesScreen(viewModel, keys, onClose) }
      }
    }
  }
}

/** macOS puts a window's close button on the left; Windows and Linux desktops default to the right. */
private fun closeButtonAtStart(osName: String = System.getProperty("os.name").orEmpty()): Boolean = osName.startsWith("Mac", ignoreCase = true)
