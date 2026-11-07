package dev.skillbill.desktop.core.domain.qa

data class QaDevicePick(val kind: QaDevicePickKind, val value: String, val displayName: String) {
  val isValid: Boolean get() = value.isNotBlank() && value.length <= 256 && !value.startsWith("-") &&
    value.none { it.isWhitespace() || it.code in 0x00..0x1f || it.code in 0x7f..0x9f } && displayName.isNotBlank()
}

enum class QaDevicePickKind(val wireValue: String) {
  Serial("serial"),
  Avd("avd"),
}

enum class QaDeviceState { Ready, Unauthorized, Offline, Leased, Unsupported }

enum class QaDeviceTransport { Emulator, Wifi, Usb }

enum class QaDeviceMetadataState { Available, Unavailable, NotRequested }

enum class QaLeaseAvailability { Verified, Unavailable }

data class QaConnectedDevice(
  val serial: String,
  val displayName: String,
  val state: QaDeviceState,
  val unsupportedState: String?,
  val transport: QaDeviceTransport,
  val androidRelease: String?,
  val androidReleaseState: QaDeviceMetadataState,
  val runningAvdName: String?,
  val avdIdentityUncertain: Boolean,
  val leaseOwners: List<QaLeaseOwner>,
  val leaseAvailability: QaLeaseAvailability,
) {
  val pick: QaDevicePick? get() = if (state == QaDeviceState.Ready) QaDevicePick(QaDevicePickKind.Serial, serial, displayName) else null
}

data class QaLeaseOwner(val invocationId: String, val runLabel: String)

data class QaStoppedAvd(val name: String) {
  val pick: QaDevicePick get() = QaDevicePick(QaDevicePickKind.Avd, name, name)
}

data class QaDeviceCounts(val connected: Int, val ready: Int)

sealed interface QaDeviceDiscovery {
  data object AdbMissing : QaDeviceDiscovery

  data class AdbFailed(val reason: String) : QaDeviceDiscovery

  data class Listed(
    val connected: List<QaConnectedDevice>,
    val stoppedAvds: List<QaStoppedAvd>,
    val counts: QaDeviceCounts,
    val emulatorListAvailable: Boolean,
  ) : QaDeviceDiscovery
}

interface QaDeviceDiscoveryRepository {
  suspend fun refresh(
    repositoryRoots: List<String>,
    invocationRunLabels: Map<String, String> = emptyMap(),
  ): QaDeviceDiscovery
}
