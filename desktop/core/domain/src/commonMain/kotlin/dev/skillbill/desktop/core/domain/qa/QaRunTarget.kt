package dev.skillbill.desktop.core.domain.qa

data class QaRunTarget(
  val pick: QaDevicePick,
  val boots: Boolean,
  val auto: Boolean,
  val sourcePick: QaDevicePick?,
  val monospace: Boolean,
)

fun resolveQaRunTarget(discovery: QaDeviceDiscovery?, pick: QaDevicePick?): QaRunTarget? {
  val listed = discovery as? QaDeviceDiscovery.Listed ?: return null
  val selected = when (pick?.kind) {
    QaDevicePickKind.Serial -> listed.connected.firstOrNull {
      it.serial == pick.value && it.state in setOf(QaDeviceState.Ready, QaDeviceState.Leased)
    }?.let { device -> device.toRunTarget(pick, false, false) }
    QaDevicePickKind.Avd -> listed.stoppedAvds.firstOrNull { it.name == pick.value }
      ?.let { QaRunTarget(it.pick, true, false, pick, true) }
      ?: listed.connected.firstOrNull {
        it.runningAvdName == pick.value && it.state in setOf(QaDeviceState.Ready, QaDeviceState.Leased)
      }
        ?.let { device -> device.toRunTarget(QaDevicePick(QaDevicePickKind.Serial, device.serial, device.displayName), false, false, pick) }
    null -> null
  }
  return selected ?: listed.connected.firstOrNull { it.state == QaDeviceState.Ready }
    ?.let { device -> device.toRunTarget(device.pick!!, false, true, pick) }
    ?: listed.stoppedAvds.firstOrNull()?.let { QaRunTarget(it.pick, true, true, pick, true) }
}

private fun QaConnectedDevice.toRunTarget(target: QaDevicePick, boots: Boolean, auto: Boolean, sourcePick: QaDevicePick? = target) =
  QaRunTarget(target, boots, auto, sourcePick, transport == QaDeviceTransport.Emulator)
