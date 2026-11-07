package dev.skillbill.desktop.app.shell.qadevice

import dev.skillbill.desktop.core.domain.qa.QaConnectedDevice
import dev.skillbill.desktop.core.domain.qa.QaDeviceCounts
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDeviceMetadataState
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import dev.skillbill.desktop.core.domain.qa.QaDeviceState
import dev.skillbill.desktop.core.domain.qa.QaDeviceTransport
import dev.skillbill.desktop.core.domain.qa.QaLeaseAvailability
import dev.skillbill.desktop.core.domain.qa.QaLeaseOwner
import dev.skillbill.desktop.core.domain.qa.QaStoppedAvd
import kotlin.test.Test
import kotlin.test.assertEquals

class QaDevicePresentationTest {
  @Test
  fun `keyboard movement skips disabled rows and wraps`() {
    val rows = listOf(
      QaDeviceRow.Auto,
      QaDeviceRow.Device(device(QaDeviceState.Leased)),
      QaDeviceRow.Device(device(QaDeviceState.Unauthorized)),
      QaDeviceRow.StoppedAvd(QaStoppedAvd("Pixel_API_35")),
    )

    assertEquals(3, stepPickable(rows, 0, 1))
    assertEquals(0, stepPickable(rows, 3, 1))
    assertEquals(0, stepPickable(rows, 3, -1))
    assertEquals(3, stepPickable(rows, 0, -1))
    assertEquals(0, stepPickable(rows, -1, 1))
    assertEquals(3, stepPickable(rows, -1, -1))
  }

  @Test
  fun `closed pip follows failure warning selected readiness and off`() {
    val ready = device(QaDeviceState.Ready)
    val offline = device(QaDeviceState.Offline, serial = "offline")
    val readyPick = ready.pick!!
    val listed = QaDeviceDiscovery.Listed(listOf(ready, offline), emptyList(), QaDeviceCounts(2, 1), true)
    val idle = QaDeviceDiscovery.Listed(emptyList(), listOf(QaStoppedAvd("Pixel_API_35")), QaDeviceCounts(0, 0), true)

    assertEquals(QaDevicePip.Failed, qaDeviceClosedState(null, QaDeviceDiscovery.AdbMissing).pip)
    assertEquals(QaDevicePip.Blocked, qaDeviceClosedState(null, listed).pip)
    assertEquals(QaDevicePip.Done, qaDeviceClosedState(readyPick, QaDeviceDiscovery.Listed(listOf(ready), emptyList(), QaDeviceCounts(1, 1), true)).pip)
    assertEquals(QaDevicePip.Off, qaDeviceClosedState(QaDevicePick(QaDevicePickKind.Avd, "Pixel_API_35", "Pixel API 35"), idle).pip)
  }

  @Test
  fun `closed state reports readiness missing picks and pre-discovery selection`() {
    val ready = device(QaDeviceState.Ready)
    val noReady = QaDeviceDiscovery.Listed(listOf(device(QaDeviceState.Offline)), emptyList(), QaDeviceCounts(1, 0), true)
    val none = QaDeviceDiscovery.Listed(emptyList(), emptyList(), QaDeviceCounts(0, 0), true)
    val pick = QaDevicePick(QaDevicePickKind.Serial, "missing", "Pixel 8")

    assertEquals("1 ready", qaDeviceClosedState(null, QaDeviceDiscovery.Listed(listOf(ready), emptyList(), QaDeviceCounts(1, 1), true)).subtitle)
    assertEquals("No ready device · QA boots an AVD", qaDeviceClosedState(null, noReady).subtitle)
    assertEquals("No devices", qaDeviceClosedState(null, none).subtitle)
    assertEquals("Not connected", qaDeviceClosedState(pick, none).subtitle)
    assertEquals(QaDeviceClosedState("Pixel 8", "", false, QaDeviceGlyph.Phone, QaDevicePip.Off), qaDeviceClosedState(pick, null))
  }

  @Test
  fun `selected AVD follows its running emulator and missing picks remain selected`() {
    val running = device(QaDeviceState.Ready, serial = "emulator-5554", transport = QaDeviceTransport.Emulator, runningAvdName = "Pixel_API_35")
    val listed = QaDeviceDiscovery.Listed(listOf(running), listOf(QaStoppedAvd("Pixel_API_36")), QaDeviceCounts(1, 1), true)
    val runningPick = QaDevicePick(QaDevicePickKind.Avd, "Pixel_API_35", "Pixel API 35")
    val missingPick = QaDevicePick(QaDevicePickKind.Avd, "Pixel_API_34", "Pixel API 34")

    val rows = qaDeviceRows(runningPick, listed)
    assertEquals(1, selectedRowIndex(rows, runningPick))
    val missingRows = qaDeviceRows(missingPick, listed)
    assertEquals(2, selectedRowIndex(missingRows, missingPick))
    assertEquals(missingPick, (missingRows[2] as QaDeviceRow.MissingPick).pick)
  }

  @Test
  fun `an undiscovered stored pick does not mark automatic selection`() {
    val pick = QaDevicePick(QaDevicePickKind.Serial, "pixel-serial", "Pixel 8")
    val results = listOf(null, QaDeviceDiscovery.AdbMissing, QaDeviceDiscovery.AdbFailed("devices_command_failed"))

    results.forEach { result ->
      val rows = qaDeviceRows(pick, result)
      assertEquals(-1, selectedRowIndex(rows, pick))
      assertEquals(0, selectedRowIndex(rows, null))
    }
  }

  private fun device(
    state: QaDeviceState,
    serial: String = "pixel-serial",
    transport: QaDeviceTransport = QaDeviceTransport.Usb,
    runningAvdName: String? = null,
  ) = QaConnectedDevice(
    serial = serial,
    displayName = "Pixel 8",
    state = state,
    unsupportedState = null,
    transport = transport,
    androidRelease = "15",
    androidReleaseState = QaDeviceMetadataState.Available,
    runningAvdName = runningAvdName,
    avdIdentityUncertain = false,
    leaseOwners = if (state == QaDeviceState.Leased) listOf(QaLeaseOwner("run", "SKILL-1")) else emptyList(),
    leaseAvailability = QaLeaseAvailability.Verified,
  )
}
