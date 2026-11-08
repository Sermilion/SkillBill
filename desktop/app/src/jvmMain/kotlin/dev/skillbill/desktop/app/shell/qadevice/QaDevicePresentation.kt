package dev.skillbill.desktop.app.shell.qadevice

import dev.skillbill.desktop.core.domain.qa.QaConnectedDevice
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import dev.skillbill.desktop.core.domain.qa.QaDeviceState
import dev.skillbill.desktop.core.domain.qa.QaDeviceTransport
import dev.skillbill.desktop.core.domain.qa.QaStoppedAvd

enum class QaDevicePip { Failed, Blocked, Done, Off }

enum class QaDeviceGlyph { Auto, Phone, Emulator }

data class QaDeviceClosedState(
  val title: String,
  val subtitle: String,
  val titleMono: Boolean,
  val glyph: QaDeviceGlyph,
  val pip: QaDevicePip,
)

fun qaDeviceClosedState(pick: QaDevicePick?, result: QaDeviceDiscovery?): QaDeviceClosedState {
  if (result == QaDeviceDiscovery.AdbMissing) return QaDeviceClosedState("No devices", "adb not found", false, QaDeviceGlyph.Auto, QaDevicePip.Failed)
  val listed = result as? QaDeviceDiscovery.Listed
  val failed = result as? QaDeviceDiscovery.AdbFailed
  val devices = listed?.connected.orEmpty()
  val pip = when {
    devices.any { it.state == QaDeviceState.Unauthorized || it.state == QaDeviceState.Offline } -> QaDevicePip.Blocked
    if (pick == null) devices.any { it.state == QaDeviceState.Ready } else pickedDevice(pick, listed)?.state == QaDeviceState.Ready -> QaDevicePip.Done
    else -> QaDevicePip.Off
  }
  if (pick == null) {
    val subtitle = when {
      failed != null -> "adb did not respond"
      listed == null -> "Any free ready device, or boot an AVD"
      listed.counts.ready > 0 -> "${listed.counts.ready} ready"
      devices.isNotEmpty() || listed.stoppedAvds.isNotEmpty() -> "No ready device · QA boots an AVD"
      else -> "No devices"
    }
    return QaDeviceClosedState("Let QA choose", subtitle, false, QaDeviceGlyph.Auto, pip)
  }
  if (failed != null) return QaDeviceClosedState(pick.displayName, "adb did not respond", pick.kind == QaDevicePickKind.Avd, glyphFor(pick, null), pip)
  if (listed == null) return QaDeviceClosedState(pick.displayName, "", pick.kind == QaDevicePickKind.Avd, glyphFor(pick, null), QaDevicePip.Off)
  val device = pickedDevice(pick, listed)
  if (device != null) {
    val transport = when (device.transport) {
      QaDeviceTransport.Usb -> "USB"
      QaDeviceTransport.Wifi -> "Wi-Fi"
      QaDeviceTransport.Emulator -> "Emulator"
    }
    val status = when (device.state) {
      QaDeviceState.Ready -> device.androidRelease?.let { " · Android $it" }.orEmpty()
      QaDeviceState.Leased -> " · In use"
      QaDeviceState.Unauthorized -> " · Unauthorized"
      QaDeviceState.Offline -> " · Offline"
      QaDeviceState.Unsupported -> " · Unsupported"
    }
    return QaDeviceClosedState(device.displayName, "$transport$status", device.transport == QaDeviceTransport.Emulator, glyphFor(pick, device), pip)
  }
  val stopped = listed.stoppedAvds.any { pick.kind == QaDevicePickKind.Avd && it.name == pick.value }
  return if (stopped) QaDeviceClosedState(pick.displayName, "Not running · QA boots it", true, QaDeviceGlyph.Emulator, pip)
  else QaDeviceClosedState(pick.displayName, "Not connected", pick.kind == QaDevicePickKind.Avd, glyphFor(pick, null), pip)
}

sealed interface QaDeviceRow {
  val pickable: Boolean
  fun toPick(): QaDevicePick?

  data object Auto : QaDeviceRow {
    override val pickable = true
    override fun toPick(): QaDevicePick? = null
  }

  data class Device(val device: QaConnectedDevice) : QaDeviceRow {
    override val pickable get() = device.pick != null
    override fun toPick(): QaDevicePick? = device.pick
  }

  data class StoppedAvd(val avd: QaStoppedAvd) : QaDeviceRow {
    override val pickable = true
    override fun toPick(): QaDevicePick = avd.pick
  }

  data class MissingPick(val pick: QaDevicePick) : QaDeviceRow {
    override val pickable = false
    override fun toPick(): QaDevicePick? = null
  }
}

fun qaDeviceRows(pick: QaDevicePick?, result: QaDeviceDiscovery?): List<QaDeviceRow> {
  val listed = result as? QaDeviceDiscovery.Listed ?: return listOf(QaDeviceRow.Auto)
  val rows = mutableListOf<QaDeviceRow>(QaDeviceRow.Auto)
  if (pick?.kind == QaDevicePickKind.Serial && listed.connected.none { it.serial == pick.value }) rows += QaDeviceRow.MissingPick(pick)
  rows += listed.connected.map(QaDeviceRow::Device)
  if (pick?.kind == QaDevicePickKind.Avd && listed.stoppedAvds.none { it.name == pick.value } && listed.connected.none { it.runningAvdName == pick.value }) {
    rows += QaDeviceRow.MissingPick(pick)
  }
  rows += listed.stoppedAvds.map(QaDeviceRow::StoppedAvd)
  return rows
}

fun selectedRowIndex(rows: List<QaDeviceRow>, pick: QaDevicePick?): Int {
  if (pick == null) return 0
  return rows.indexOfFirst { row ->
    when (row) {
      QaDeviceRow.Auto -> false
      is QaDeviceRow.Device -> if (pick.kind == QaDevicePickKind.Serial) row.device.serial == pick.value
      else row.device.runningAvdName == pick.value
      is QaDeviceRow.StoppedAvd -> pick.kind == QaDevicePickKind.Avd && row.avd.name == pick.value
      is QaDeviceRow.MissingPick -> row.pick == pick
    }
  }
}

fun stepPickable(rows: List<QaDeviceRow>, from: Int, direction: Int): Int {
  if (rows.isEmpty()) return from
  val step = if (direction < 0) -1 else 1
  var index = if (from in rows.indices) from else if (step > 0) rows.lastIndex else 0
  repeat(rows.size) {
    index = (index + step + rows.size) % rows.size
    if (rows[index].pickable) return index
  }
  return from
}

private fun pickedDevice(pick: QaDevicePick, listed: QaDeviceDiscovery.Listed?): QaConnectedDevice? = when (pick.kind) {
  QaDevicePickKind.Serial -> listed?.connected?.firstOrNull { it.serial == pick.value }
  QaDevicePickKind.Avd -> listed?.connected?.firstOrNull { it.runningAvdName == pick.value }
}

private fun glyphFor(pick: QaDevicePick, device: QaConnectedDevice?): QaDeviceGlyph =
  if (pick.kind == QaDevicePickKind.Avd || device?.transport == QaDeviceTransport.Emulator) QaDeviceGlyph.Emulator else QaDeviceGlyph.Phone
