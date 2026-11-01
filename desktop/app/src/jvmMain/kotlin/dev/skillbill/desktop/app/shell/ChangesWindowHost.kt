package dev.skillbill.desktop.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import dev.skillbill.desktop.app.display.DesktopUiScale
import dev.skillbill.desktop.app.display.DesktopUiScaleProvider
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.skillBillIcon
import dev.skillbill.desktop.core.domain.model.ChangeScope
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.ui.changes.ChangesWindowEntry
import dev.skillbill.desktop.feature.changes.ChangesScreen
import dev.skillbill.desktop.feature.changes.ChangesWindowKeys
import dev.skillbill.desktop.feature.changes.ChangesWindowViewModel
import dev.skillbill.desktop.feature.changes.changesWindowTitle

@Composable
fun ApplicationScope.ChangesWindowHost(
  entry: ChangesWindowEntry,
  uiScale: DesktopUiScale,
  reducedMotion: Boolean,
  createViewModel: (WorkItem, ChangeScope) -> ChangesWindowViewModel,
  onCloseRequest: () -> Unit,
) {
  val store = remember { ViewModelStore() }
  DisposableEffect(store) { onDispose { store.clear() } }
  val viewModel = remember { createViewModel(entry.item, entry.scope).also { store.put("changes", it) } }
  LaunchedEffect(entry.focusRequest) { if (entry.focusRequest > 0) viewModel.requestScope(entry.scope) }
  val keys = remember { ChangesWindowKeys() }
  Window(
    onCloseRequest = onCloseRequest,
    onPreviewKeyEvent = keys::handle,
    title = changesWindowTitle(entry.item),
    icon = skillBillIcon(),
    state = rememberWindowState(
      size = remember(uiScale) { uiScale.windowSize(DpSize(1200.dp, 800.dp)) },
      position = WindowPosition(Alignment.Center),
    ),
  ) {
    val awtWindow = window
    SideEffect { awtWindow.minimumSize = uiScale.windowDimension(720, 480) }
    LaunchedEffect(entry.focusRequest) {
      withFrameNanos { }
      awtWindow.toFront()
      awtWindow.requestFocus()
    }
    DesktopUiScaleProvider(uiScale) { SkillBillTheme(reducedMotion) { ChangesScreen(viewModel, keys) } }
  }
}
