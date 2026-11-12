package dev.skillbill.desktop.app.shell.qadevice

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import dev.skillbill.desktop.app.shell.ShellBoardState
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.leaseHolderIssueKeys
import dev.skillbill.desktop.core.ui.apprun.RunAppDeviceHost
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(WindowScope::class)
@ContributesBinding(WindowScope::class)
class QaDevicePickerRunAppHost(
  private val state: QaDevicePickerState,
  private val board: ShellBoardState,
  scope: WindowCoroutineScope,
) : RunAppDeviceHost {
  override val pick: StateFlow<QaDevicePick?> = state.ui.map { it.pick }
    .stateIn(scope.scope, SharingStarted.Eagerly, state.ui.value.pick)
  override val discovery: StateFlow<QaDeviceDiscovery?> = state.ui.map { it.result }
    .stateIn(scope.scope, SharingStarted.Eagerly, state.ui.value.result)

  override fun refreshDevices() = state.refresh()

  override fun leaseHolders(): Map<String, String> = leaseHolderIssueKeys(board.view.value.lastLoaded?.items.orEmpty())

  override fun openDeviceMenu() = state.open(QaDeviceMenuAnchor.RunApp)

  @Composable
  override fun DeviceMenu(returnFocus: FocusRequester) {
    QaDeviceDropdown(state, returnFocus, QaDeviceMenuAnchor.RunApp, dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement.Below)
  }
}
