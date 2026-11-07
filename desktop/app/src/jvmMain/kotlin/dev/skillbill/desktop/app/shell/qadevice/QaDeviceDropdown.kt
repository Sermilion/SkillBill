package dev.skillbill.desktop.app.shell.qadevice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import dev.skillbill.desktop.core.domain.qa.QaDeviceState
import dev.skillbill.desktop.core.domain.qa.QaDeviceTransport
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
internal fun QaDeviceDropdown(state: QaDevicePickerState, switcherFocus: FocusRequester) {
  val ui by state.ui.collectAsState()
  val rows = qaDeviceRows(ui.pick, ui.result)
  if (ui.open) ShellLayer(state::close)
  SbPopover(
    expanded = ui.open,
    onDismissRequest = state::close,
    width = 300.dp,
    maxWidth = 300.dp,
    placement = SbPopoverPlacement.Above,
    returnFocusTo = switcherFocus,
    arrowKeysMoveFocus = false,
    keyboardShortcuts = mapOf(
      Key.DirectionUp to { state.move(-1) },
      Key.DirectionDown to { state.move(1) },
      Key.R to state::refresh,
    ),
  ) {
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          BasicText("QA device", style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg))
          val count = (ui.result as? QaDeviceDiscovery.Listed)?.counts?.connected ?: 0
          BasicText("$count connected", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
        }
        SbIconButton(
          LucideIcons.refresh,
          "Refresh (R)",
          state::refresh,
          modifier = Modifier.onFocusChanged { if (it.isFocused) state.focus(-1) },
          size = SbControlSize.Small,
        )
      }
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
        val selected = selectedRowIndex(rows, ui.pick)
        QaDeviceOption(
          row = QaDeviceRow.Auto,
          index = 0,
          active = ui.keyboardIndex == 0,
          selected = selected == 0,
          icon = LucideIcons.sparkles,
          title = "Let QA choose",
          subtitle = "Any free ready device, or boot an AVD",
          onChoose = state::choose,
          onFocus = state::focus,
        )
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(SkillBillTheme.colors.borderSubtle))
        when (val result = ui.result) {
          QaDeviceDiscovery.AdbMissing -> EmptyState("adb not found", "Install Android SDK Platform-Tools, or set ANDROID_HOME so the app can find platform-tools/adb.")
          is QaDeviceDiscovery.AdbFailed -> EmptyState("adb did not respond", result.reason)
          else -> {
            val connected = rows.withIndex().filter { (_, row) -> row is QaDeviceRow.Device || row is QaDeviceRow.MissingPick && row.pick.kind == QaDevicePickKind.Serial }
            GroupLabel("Connected")
            if (connected.isEmpty()) EmptyState("No phone or running emulator. Plug in a phone with USB debugging on.")
            connected.forEach { (index, row) ->
              key(row) { ConnectedOption(row, index, ui.keyboardIndex == index, selected == index, state::choose, state::focus) }
            }
            val avds = rows.withIndex().filter { (_, row) -> row is QaDeviceRow.StoppedAvd || row is QaDeviceRow.MissingPick && row.pick.kind == QaDevicePickKind.Avd }
            GroupLabel("Emulators")
            if (avds.isEmpty()) EmptyState("No AVDs. Create one in Android Studio's Device Manager.")
            avds.forEach { (index, row) ->
              key(row) { EmulatorOption(row, index, ui.keyboardIndex == index, selected == index, state::choose, state::focus) }
            }
          }
        }
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BasicText(if (ui.checking) "Checking…" else "Checked just now", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
        BasicText("↑ ↓ ↵", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
      }
    }
  }
}

@Composable
private fun ConnectedOption(row: QaDeviceRow, index: Int, active: Boolean, selected: Boolean, onChoose: (Int) -> Unit, onFocus: (Int) -> Unit) {
  when (row) {
    is QaDeviceRow.Device -> DeviceOption(row, index, active, selected, onChoose, onFocus)
    is QaDeviceRow.MissingPick -> QaDeviceOption(row, index, active, selected, LucideIcons.smartphone, row.pick.displayName, "Not connected", onChoose, onFocus, mono = row.pick.kind == QaDevicePickKind.Avd)
    else -> Unit
  }
}

@Composable
private fun EmulatorOption(row: QaDeviceRow, index: Int, active: Boolean, selected: Boolean, onChoose: (Int) -> Unit, onFocus: (Int) -> Unit) {
  when (row) {
    is QaDeviceRow.StoppedAvd -> QaDeviceOption(row, index, active, selected, LucideIcons.monitor, row.avd.name, "Not running · QA boots it", onChoose, onFocus, mono = true)
    is QaDeviceRow.MissingPick -> QaDeviceOption(row, index, active, selected, LucideIcons.monitor, row.pick.displayName, "Not connected", onChoose, onFocus, mono = true)
    else -> Unit
  }
}

@Composable
private fun DeviceOption(row: QaDeviceRow.Device, index: Int, active: Boolean, selected: Boolean, onChoose: (Int) -> Unit, onFocus: (Int) -> Unit) {
  val device = row.device
  val colors = SkillBillTheme.colors
  val status = when (device.state) {
    QaDeviceState.Ready -> "Ready"
    QaDeviceState.Leased -> "In use by ${device.leaseOwners.firstOrNull()?.runLabel ?: "another run"}"
    QaDeviceState.Unauthorized -> "Unauthorized"
    QaDeviceState.Offline -> "Offline"
    QaDeviceState.Unsupported -> device.unsupportedState ?: "Unsupported"
  }
  val stateColor = when (device.state) {
    QaDeviceState.Ready -> colors.stDone
    QaDeviceState.Leased, QaDeviceState.Unauthorized, QaDeviceState.Offline -> colors.stBlocked
    QaDeviceState.Unsupported -> colors.fgFaint
  }
  QaDeviceOption(
    row,
    index,
    active,
    selected,
    if (device.transport == QaDeviceTransport.Emulator) LucideIcons.monitor else LucideIcons.smartphone,
    device.displayName,
    status,
    onChoose,
    onFocus,
    mono = device.transport == QaDeviceTransport.Emulator,
    statusColor = stateColor,
    transport = device.transport,
    androidRelease = device.androidRelease,
  )
  if (device.state == QaDeviceState.Unauthorized) BasicText(
    "Unlock the phone and accept the USB debugging prompt.",
    Modifier.padding(start = 38.dp, end = 8.dp, bottom = 6.dp),
    style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint),
  )
}

@Composable
private fun QaDeviceOption(
  row: QaDeviceRow,
  index: Int,
  active: Boolean,
  selected: Boolean,
  icon: ImageVector,
  title: String,
  subtitle: String,
  onChoose: (Int) -> Unit,
  onFocus: (Int) -> Unit,
  mono: Boolean = false,
  statusColor: Color? = null,
  transport: QaDeviceTransport? = null,
  androidRelease: String? = null,
) {
  val colors = SkillBillTheme.colors
  val requester = remember { BringIntoViewRequester() }
  val focus = remember { FocusRequester() }
  LaunchedEffect(active) {
    if (active) {
      if (row.pickable) focus.requestFocus()
      requester.bringIntoView()
    }
  }
  Row(
    Modifier.fillMaxWidth().bringIntoViewRequester(requester)
      .background(if (active) colors.bgHover else Color.Transparent, RoundedCornerShape(SkillBillRadius.sm))
      .focusRequester(focus)
      .onFocusChanged { if (it.isFocused) onFocus(index) }
      .onPreviewKeyEvent { event ->
        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) onChoose(index)
        true
      }
      .selectable(selected = selected, enabled = row.pickable, role = Role.RadioButton) { onChoose(index) }
      .padding(horizontal = 7.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    SbIcon(icon, SbIconSize.Sm, modifier = Modifier.padding(start = 1.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      BasicText(title, style = (if (mono) SkillBillTheme.type.monoSm else SkillBillTheme.type.tSm).copy(color = if (row.pickable) colors.fg else colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (statusColor != null) Box(Modifier.size(6.dp).background(statusColor, CircleShape))
        BasicText(subtitle, style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (transport) {
          QaDeviceTransport.Wifi -> SbIcon(LucideIcons.wifi, SbIconSize.Xs, contentDescription = "Wi-Fi")
          QaDeviceTransport.Usb -> BasicText("USB", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
          QaDeviceTransport.Emulator, null -> Unit
        }
        androidRelease?.let { BasicText("Android $it", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint), maxLines = 1) }
      }
    }
    if (selected) SbIcon(LucideIcons.check, SbIconSize.Sm)
  }
}

@Composable
private fun GroupLabel(text: String) {
  BasicText(text, Modifier.padding(start = 7.dp, top = 8.dp, bottom = 4.dp), style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
}

@Composable
private fun EmptyState(title: String, detail: String? = null) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    BasicText(title, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgMuted))
    detail?.let { BasicText(it, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint)) }
  }
}
