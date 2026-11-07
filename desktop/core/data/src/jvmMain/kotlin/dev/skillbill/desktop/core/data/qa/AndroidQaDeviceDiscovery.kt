package dev.skillbill.desktop.core.data.qa

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.qa.QaConnectedDevice
import dev.skillbill.desktop.core.domain.qa.QaDeviceCounts
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscoveryRepository
import dev.skillbill.desktop.core.domain.qa.QaDeviceMetadataState
import dev.skillbill.desktop.core.domain.qa.QaDeviceState
import dev.skillbill.desktop.core.domain.qa.QaDeviceTransport
import dev.skillbill.desktop.core.domain.qa.QaLeaseAvailability
import dev.skillbill.desktop.core.domain.qa.QaLeaseOwner
import dev.skillbill.desktop.core.domain.qa.QaStoppedAvd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skillbill.ports.qa.model.QaDeviceLeaseSnapshot
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest

internal class AndroidQaDeviceDiscovery(
  private val sdkLocator: AndroidQaSdkLocator,
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
  private val runner: QaCommandRunner,
) : QaDeviceDiscoveryRepository {
  override suspend fun refresh(repositoryRoots: List<String>, invocationRunLabels: Map<String, String>): QaDeviceDiscovery {
    var rootsValid = true
    val roots = repositoryRoots.mapNotNull { raw ->
      try {
        Path.of(raw).toAbsolutePath().normalize()
      } catch (_: InvalidPathException) {
        rootsValid = false
        diagnostics.record("desktop_qa_repository_root_invalid")
        null
      }
    }.distinct()
    val session = sessions.sessions.value
    val tools = withContext(Dispatchers.IO) { sdkLocator.locate(repositoryRoots) }
      ?: return QaDeviceDiscovery.AdbMissing
    val devicesResult = runner.run(tools.adb, listOf("devices", "-l"))
    if (devicesResult.failureReason != null) {
      diagnostics.record("desktop_qa_adb_devices_failed")
      return QaDeviceDiscovery.AdbFailed(devicesResult.failureReason)
    }
    if (devicesResult.exitCode != 0) {
      diagnostics.record("desktop_qa_adb_devices_failed")
      return QaDeviceDiscovery.AdbFailed("devices_command_failed")
    }
    val leases = withContext(Dispatchers.IO) { readLeases(session, roots, rootsValid) }
    val parsed = AndroidQaDeviceParsing.devices(devicesResult.stdout)
    val rows = parsed.map { device ->
      val baseState = AndroidQaDeviceClassifier.adbState(device.adbState)
      val fingerprint = sha256(device.serial.trim())
      val owners = leases.holders[fingerprint].orEmpty().distinct().map { invocationId ->
        QaLeaseOwner(invocationId, invocationRunLabels[invocationId]?.takeIf(String::isNotBlank) ?: invocationId)
      }
      val state = AndroidQaDeviceClassifier.state(device.adbState, owners.isNotEmpty())
      var release: String? = null
      var avdName: String? = null
      var avdUncertain = false
      var metadataFailed = false
      var releaseState = QaDeviceMetadataState.NotRequested
      val transport = AndroidQaDeviceParsing.transport(device.serial)
      if (state == QaDeviceState.Ready) {
        release = probe(tools.adb, listOf("-s", device.serial, "shell", "getprop", "ro.build.version.release"))
          ?.trim()?.takeIf(String::isNotEmpty)
        releaseState = if (release == null) QaDeviceMetadataState.Unavailable else QaDeviceMetadataState.Available
        if (release == null) metadataFailed = true
      }
      if (transport == QaDeviceTransport.Emulator) {
        avdName = probe(tools.adb, listOf("-s", device.serial, "emu", "avd", "name"))
          ?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
        if (avdName == null) {
          avdUncertain = true
          metadataFailed = true
          diagnostics.record("desktop_qa_emulator_identity_unavailable")
        }
      }
      if (device.model == null) diagnostics.record("desktop_qa_device_name_fallback")
      if (metadataFailed) diagnostics.record("desktop_qa_device_metadata_unavailable")
      QaConnectedDevice(
        serial = device.serial,
        displayName = device.model ?: device.serial,
        state = state,
        unsupportedState = device.adbState.takeIf { baseState == QaDeviceState.Unsupported },
        transport = transport,
        androidRelease = release,
        androidReleaseState = releaseState,
        runningAvdName = avdName,
        avdIdentityUncertain = avdUncertain,
        leaseOwners = owners,
        leaseAvailability = leases.availability,
      )
    }
    val avdCommand = tools.emulator?.let { runner.run(it, listOf("-list-avds")) }
    val avdResult = avdCommand?.takeIf { it.failureReason == null && it.exitCode == 0 }?.stdout
    if (avdResult == null) diagnostics.record("desktop_qa_emulator_list_unavailable")
    val runningNames = rows.mapNotNull { it.runningAvdName }.toSet()
    val uncertainRunning = rows.any { it.avdIdentityUncertain }
    val stopped = avdResult?.let(AndroidQaDeviceParsing::avds).orEmpty()
      .filterNot { it in runningNames }
      .filterNot { uncertainRunning }
      .map(::QaStoppedAvd)
    if (sessions.sessions.value !== session || (session != null && !sessions.isCurrent(session))) {
      diagnostics.record("desktop_qa_discovery_session_changed")
      return QaDeviceDiscovery.AdbFailed("session_changed")
    }
    return QaDeviceDiscovery.Listed(
      connected = rows,
      stoppedAvds = stopped,
      counts = QaDeviceCounts(rows.size, rows.count { it.state == QaDeviceState.Ready }),
      emulatorListAvailable = avdResult != null,
    )
  }

  private fun readLeases(
    session: dev.skillbill.desktop.core.data.session.DatabaseSessionComponent?,
    roots: List<Path>,
    rootsValid: Boolean,
  ): LeaseRead {
    if (session == null) {
      diagnostics.record("desktop_qa_lease_snapshot_unavailable")
      return LeaseRead(emptyMap(), QaLeaseAvailability.Unavailable)
    }
    val holders = mutableMapOf<String, MutableList<String>>()
    var available = rootsValid
    roots.forEach { root ->
      when (val result = session.runtime.qaDeviceLeasePort.liveHolders(root)) {
        is QaDeviceLeaseSnapshot.Holders -> result.leases.forEach { lease ->
          holders.getOrPut(lease.deviceFingerprint) { mutableListOf() }.add(lease.invocationId)
        }
        is QaDeviceLeaseSnapshot.Unavailable -> {
          available = false
          diagnostics.record("desktop_qa_lease_snapshot_unavailable")
        }
      }
    }
    if (roots.isEmpty()) {
      available = false
      diagnostics.record("desktop_qa_lease_snapshot_unavailable")
    }
    return LeaseRead(holders, if (available) QaLeaseAvailability.Verified else QaLeaseAvailability.Unavailable)
  }

  private suspend fun probe(adb: Path, arguments: List<String>): String? = runner.run(adb, arguments)
    .takeIf { it.failureReason == null && it.exitCode == 0 }?.stdout

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

  private data class LeaseRead(val holders: Map<String, List<String>>, val availability: QaLeaseAvailability)
}
