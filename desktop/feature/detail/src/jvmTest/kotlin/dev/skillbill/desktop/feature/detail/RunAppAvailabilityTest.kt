package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkKind
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
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RunAppAvailabilityTest {
  @Test
  fun `availability hides ineligible work and keeps checkout edits off`() {
    val ready = listed(device("phone", QaDeviceState.Ready))
    val done = workItem("NEWS-168", lifecycle = Lifecycle.Terminal).copy(appRunPlatforms = setOf("android"))
    val cases = listOf(
      "platform absent" to runAppAvailability(done.copy(appRunPlatforms = emptySet()), ready, null, false),
      "phase" to runAppAvailability(done.copy(kind = WorkKind.Phase), ready, null, false),
      "blocked goal" to runAppAvailability(done.copy(lifecycle = Lifecycle.Blocked), ready, null, false),
      "active implementation" to runAppAvailability(done.copy(lifecycle = Lifecycle.Active), ready, null, false),
      "CI repair" to runAppAvailability(done.copy(control = done.control.copy(phaseId = "monitor_fix")), ready, null, false),
      "failed refresh" to runAppAvailability(done, ready, null, true),
    )

    assertIs<RunAppAvailability.Hidden>(cases[0].second)
    assertIs<RunAppAvailability.Hidden>(cases[1].second)
    assertEquals(RUN_APP_LIFECYCLE_REASON, (cases[2].second as RunAppAvailability.Disabled).reason)
    assertEquals(RUN_APP_LIFECYCLE_REASON, (cases[3].second as RunAppAvailability.Disabled).reason)
    assertEquals(RUN_APP_REPAIR_REASON, (cases[4].second as RunAppAvailability.Disabled).reason)
    assertEquals(RUN_APP_REFRESH_REASON, (cases[5].second as RunAppAvailability.Disabled).reason)
    assertIs<RunAppAvailability.Enabled>(runAppAvailability(done, ready, null, false))
    assertIs<RunAppAvailability.Enabled>(runAppAvailability(done.copy(lifecycle = Lifecycle.Active, control = done.control.copy(phaseId = "monitor")), ready, null, false))
  }

  @Test
  fun `availability reports discovery failures and resolves automatic or stale picks against listed devices`() {
    val item = workItem("NEWS-168", lifecycle = Lifecycle.Terminal).copy(appRunPlatforms = setOf("android"))
    val phone = device("phone", QaDeviceState.Ready)
    val leased = device("leased", QaDeviceState.Leased)
    val ready = listed(leased, phone, stopped = listOf("Pixel_7_API_34", "Tablet_API_35"))

    assertEquals(RUN_APP_ADB_MISSING_REASON, (runAppAvailability(item, QaDeviceDiscovery.AdbMissing, null, false) as RunAppAvailability.Disabled).reason)
    assertEquals(RUN_APP_ADB_FAILED_REASON, (runAppAvailability(item, QaDeviceDiscovery.AdbFailed("devices_command_failed"), null, false) as RunAppAvailability.Disabled).reason)
    assertEquals(RUN_APP_LOOKING_REASON, (runAppAvailability(item, null, null, false) as RunAppAvailability.Disabled).reason)
    assertEquals(RUN_APP_NO_DEVICE_REASON, (runAppAvailability(item, listed(), null, false) as RunAppAvailability.Disabled).reason)

    val automatic = (runAppAvailability(item, ready, null, false) as RunAppAvailability.Enabled).target
    assertEquals("phone", automatic.pick.value)
    assertEquals(true, automatic.auto)

    val picked = QaDevicePick(QaDevicePickKind.Serial, "leased", "Old name")
    val selected = (runAppAvailability(item, ready, picked, false) as RunAppAvailability.Enabled).target
    assertEquals("leased", selected.pick.value)
    assertEquals(picked, selected.sourcePick)

    val avdOnly = listed(stopped = listOf("Pixel_7_API_34", "Tablet_API_35"))
    val avd = (runAppAvailability(item, avdOnly, null, false) as RunAppAvailability.Enabled).target
    assertEquals("Pixel_7_API_34", avd.pick.value)
    assertEquals(true, avd.boots)
    assertNull(avd.sourcePick)
  }

  @Test
  fun `offline running avd falls back to a ready device`() {
    val item = workItem("NEWS-168", lifecycle = Lifecycle.Terminal).copy(appRunPlatforms = setOf("android"))
    val offline = device("emulator-5554", QaDeviceState.Offline).copy(runningAvdName = "Pixel_API_35")
    val ready = device("phone", QaDeviceState.Ready)
    val pick = QaDevicePick(QaDevicePickKind.Avd, "Pixel_API_35", "Pixel_API_35")

    val target = (runAppAvailability(item, listed(offline, ready), pick, false) as RunAppAvailability.Enabled).target

    assertEquals("phone", target.pick.value)
    assertEquals(true, target.auto)
  }

  private fun listed(vararg devices: QaConnectedDevice, stopped: List<String> = emptyList()) = QaDeviceDiscovery.Listed(
    connected = devices.toList(),
    stoppedAvds = stopped.map(::QaStoppedAvd),
    counts = QaDeviceCounts(devices.size, devices.count { it.state == QaDeviceState.Ready }),
    emulatorListAvailable = true,
  )

  private fun device(serial: String, state: QaDeviceState) = QaConnectedDevice(
    serial = serial,
    displayName = serial,
    state = state,
    unsupportedState = null,
    transport = QaDeviceTransport.Usb,
    androidRelease = null,
    androidReleaseState = QaDeviceMetadataState.NotRequested,
    runningAvdName = null,
    avdIdentityUncertain = false,
    leaseOwners = if (state == QaDeviceState.Leased) listOf(QaLeaseOwner("inv", "NEWS-170")) else emptyList(),
    leaseAvailability = QaLeaseAvailability.Verified,
  )
}
