package skillbill.infrastructure.host.process

import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

private const val LINUX_START_TICKS_PREFIX = "linux-start-ticks:"
private const val START_TIME_INDEX_AFTER_COMM = 19

internal fun processBirthToken(
  handle: ProcessHandle,
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): String? {
  if (!platform.osName.startsWith("Linux", ignoreCase = true)) {
    return handle.info().startInstant().orElse(null)?.toEpochMilli()?.toString()
  }
  val stat =
    try {
      Files.readString(Path.of("/proc/${handle.pid()}/stat"))
    } catch (failure: IOException) {
      diagnostics.warning(
        "seam=processBirthToken expected=kernel start ticks used=unavailable pid=${handle.pid()}",
        failure,
      )
      return null
    }
  val fields = stat.substringAfterLast(')', "").trim().split(Regex("\\s+"))
  val ticks = fields.getOrNull(START_TIME_INDEX_AFTER_COMM)?.toLongOrNull()
  if (ticks == null || ticks < 0) {
    diagnostics.warning(
      "seam=processBirthToken expected=kernel start ticks used=unavailable " +
        "cause=malformed_proc_stat pid=${handle.pid()}",
    )
    return null
  }
  return "$LINUX_START_TICKS_PREFIX$ticks"
}

internal fun processBirthMatches(
  handle: ProcessHandle,
  stored: String,
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): Boolean? {
  if (stored.startsWith(LINUX_START_TICKS_PREFIX)) {
    return processBirthToken(handle, platform, diagnostics)?.let { it == stored }
  }
  val birth = handle.info().startInstant().orElse(null)?.toEpochMilli()?.toString() ?: return null
  return when {
    birth == stored -> true
    platform.osName.startsWith("Linux", ignoreCase = true) && stored.toLongOrNull() != null -> {
      diagnostics.warning(
        "seam=processBirthMatches expected=stable kernel start ticks " +
          "used=unverifiable_legacy_timestamp pid=${handle.pid()}",
      )
      null
    }
    else -> false
  }
}

private const val INIT_PID: Long = 1L
private const val BOOT_IDENTITY_UNAVAILABLE: String = "boot-identity-unavailable"

private const val LEGACY_BOOT_IDENTITY_PREFIX: String = "fallback-"

private fun bootIdentitiesConflict(
  stored: String,
  local: String,
): Boolean = bootIdentityIsDecisive(stored) && bootIdentityIsDecisive(local) && stored != local

private fun bootIdentityIsDecisive(value: String): Boolean =
  value.isNotBlank() &&
    value != BOOT_IDENTITY_UNAVAILABLE &&
    !value.startsWith(LEGACY_BOOT_IDENTITY_PREFIX)

internal fun inspectRecordedProcess(
  recorded: FeatureTaskRuntimeProcessIdentity,
  local: FeatureTaskRuntimeProcessIdentity,
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeProcessInspection {
  if (bootIdentitiesConflict(recorded.bootIdentity, local.bootIdentity)) {
    return FeatureTaskRuntimeProcessInspection.NotRunning
  }
  val handle =
    ProcessHandle.of(recorded.pid).orElse(null)?.takeIf { it.isAlive }
      ?: return FeatureTaskRuntimeProcessInspection.NotRunning
  val matches = processBirthMatches(handle, recorded.processBirthToken, platform, diagnostics)
  return when {
    matches == null ->
      FeatureTaskRuntimeProcessInspection.Unsupported("Worker PID has no verifiable birth evidence.")
    matches -> FeatureTaskRuntimeProcessInspection.ExactLive
    else -> FeatureTaskRuntimeProcessInspection.PidReused
  }
}

internal fun currentProcessIdentity(
  platform: HostPlatformPort,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeProcessIdentity {
  val handle = ProcessHandle.current()
  return FeatureTaskRuntimeProcessIdentity(
    hostIdentity = InetAddress.getLocalHost().hostName,
    bootIdentity = processBootIdentity(diagnostics),
    pid = handle.pid(),
    processBirthToken =
      processBirthToken(handle, platform, diagnostics)
        ?: error("The current process does not expose process-birth evidence."),
  )
}

internal fun processBootIdentity(diagnostics: RuntimeDiagnostics): String {
  val linuxBootId = Path.of("/proc/sys/kernel/random/boot_id")
  if (Files.isReadable(linuxBootId)) {
    val bootId =
      try {
        Files.readString(linuxBootId).trim()
      } catch (failure: IOException) {
        diagnostics.warning("seam=processBootIdentity expected=kernel boot id used=init-process fallback", failure)
        null
      }
    bootId
      ?.takeIf(String::isNotBlank)
      ?.let { return it }
  }
  val initProcessBirth =
    catchingNonCancellation { ProcessHandle.of(INIT_PID).flatMap { it.info().startInstant() }.orElse(null) }
  initProcessBirth.getOrNull()?.let { return "boot-${it.toEpochMilli()}" }
  val error = initProcessBirth.exceptionOrNull()
  diagnostics.warning(
    "seam=processBootIdentity expected=kernel boot id or init-process birth " +
      "used=$BOOT_IDENTITY_UNAVAILABLE. This platform exposes neither; process-birth evidence remains " +
      "the reused-pid guard.",
    error,
  )
  return BOOT_IDENTITY_UNAVAILABLE
}
