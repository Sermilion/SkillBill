package dev.skillbill.desktop.core.ui.apprun

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import kotlinx.coroutines.flow.StateFlow

/** Window-owned device picker surface used by detail without depending on the app module. */
interface RunAppDeviceHost {
  val pick: StateFlow<QaDevicePick?>
  val discovery: StateFlow<QaDeviceDiscovery?>
  fun refreshDevices()
  fun leaseHolders(): Map<String, String>
  fun openDeviceMenu()
  @Composable fun DeviceMenu(returnFocus: FocusRequester)
}
