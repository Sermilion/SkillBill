package skillbill.infrastructure.workflow.qa

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaDeviceLeaseFailure
import skillbill.error.featuretask.qa.qaDeviceLeaseFailed
import skillbill.error.featuretask.qa.qaDeviceLeaseReason
import skillbill.infrastructure.workflow.monitor.RecordedProcessLiveness
import skillbill.infrastructure.workflow.monitor.livenessOf
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Inject
class FileSystemQaDeviceLeases(
  private val inspector: MonitorProcessInspector,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val environment: EnvironmentContext,
) : QaDeviceLeasePort {
  override fun acquire(
    repoRoot: Path,
    deviceFingerprint: String,
    invocationId: String,
  ): QaLeaseResult {
    val qaRoot = QaPaths.qaRoot(environment, repoRoot)
    val ownerFile = QaPaths.ownerFile(QaPaths.invocationDirectory(qaRoot, invocationId))
    val owner =
      readQaRecord(ownerFile)?.let { QaLeaseRecordCodec.decodeOwner(it, ownerFile.toString()) }
        ?: throw qaDeviceLeaseFailed("QA invocation '$invocationId' is not running in this repository.")
    val leaseFile = QaPaths.leaseFile(qaRoot, deviceFingerprint)
    return withLeaseLock(qaRoot) {
      val current = readQaRecord(leaseFile)?.let { QaLeaseRecordCodec.decodeLease(it, leaseFile.toString()) }
      when {
        current == null -> take(leaseFile, deviceFingerprint, owner)
        current.owner.invocationId == invocationId -> QaLeaseResult.Acquired(current.toLease())
        inspector.livenessOf(current.owner.process) != RecordedProcessLiveness.DEAD ->
          QaLeaseResult.Contended(current.owner.invocationId)
        else -> {
          diagnostics.warning(
            "qa device lease reclaimed: seam=qa_device_lease value_expected=free_or_live_owner " +
              "value_used=reclaimed_stale_owner kind=stale_reclaim device=$deviceFingerprint " +
              "previous_invocation=${current.owner.invocationId}",
          )
          take(leaseFile, deviceFingerprint, owner)
        }
      }
    }
  }

  override fun release(
    repoRoot: Path,
    lease: QaDeviceLease,
  ): QaLeaseReleaseOutcome =
    try {
      val qaRoot = QaPaths.qaRoot(environment, repoRoot)
      val leaseFile = QaPaths.leaseFile(qaRoot, lease.deviceFingerprint)
      withLeaseLock(qaRoot) {
        val current = readQaRecord(leaseFile)?.let { QaLeaseRecordCodec.decodeLease(it, leaseFile.toString()) }
        if (current?.owner?.invocationId == lease.invocationId) {
          delete(leaseFile)
          QaLeaseReleaseOutcome.Released(lease.deviceFingerprint)
        } else {
          QaLeaseReleaseOutcome.NotHeld(lease.deviceFingerprint)
        }
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
      QaLeaseReleaseOutcome.Failed(lease.deviceFingerprint, failure.qaDeviceLeaseReason)
    }

  override fun releaseAllFor(
    repoRoot: Path,
    invocationId: String,
  ): List<QaLeaseReleaseOutcome> {
    val qaRoot =
      try {
        QaPaths.qaRoot(environment, repoRoot)
      } catch (failure: SkillBillRuntimeException) {
        failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
        return listOf(QaLeaseReleaseOutcome.Failed(invocationId, failure.qaDeviceLeaseReason))
      }
    val directory = QaPaths.leasesDirectory(qaRoot)
    if (!Files.isDirectory(directory)) return emptyList()
    return try {
      withLeaseLock(qaRoot) { leaseFiles(directory).mapNotNull { file -> releaseIfOwned(file, invocationId) } }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
      listOf(QaLeaseReleaseOutcome.Failed(invocationId, failure.qaDeviceLeaseReason))
    }
  }

  private fun releaseIfOwned(
    file: Path,
    invocationId: String,
  ): QaLeaseReleaseOutcome? {
    val fingerprint = file.nameWithoutExtension
    return try {
      val current = readQaRecord(file)?.let { QaLeaseRecordCodec.decodeLease(it, file.toString()) }
      if (current?.owner?.invocationId == invocationId) {
        delete(file)
        QaLeaseReleaseOutcome.Released(fingerprint)
      } else {
        null
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
      QaLeaseReleaseOutcome.Failed(fingerprint, failure.qaDeviceLeaseReason)
    }
  }

  private fun take(
    leaseFile: Path,
    deviceFingerprint: String,
    owner: QaInvocationOwner,
  ): QaLeaseResult {
    val record = QaLeaseRecord(deviceFingerprint, owner.copy(acquiredAt = clock.instant()))
    writeQaRecordAtomically(leaseFile, QaLeaseRecordCodec.encodeLease(record))
    return QaLeaseResult.Acquired(record.toLease())
  }

  private fun leaseFiles(directory: Path): List<Path> =
    try {
      Files.list(directory).use { entries -> entries.filter { it.extension == "json" }.toList() }
    } catch (failure: IOException) {
      throw qaDeviceLeaseFailed("could not list $directory: ${failure.message}", failure)
    }

  private fun delete(file: Path) {
    try {
      Files.deleteIfExists(file)
    } catch (failure: IOException) {
      throw qaDeviceLeaseFailed("could not delete $file: ${failure.message}", failure)
    }
  }

  private fun <T> withLeaseLock(
    qaRoot: Path,
    body: () -> T,
  ): T {
    val directory = QaPaths.leasesDirectory(qaRoot)
    val channel =
      try {
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("leases.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      } catch (failure: IOException) {
        throw qaDeviceLeaseFailed("could not lock $directory: ${failure.message}", failure)
      }
    return channel.use {
      val lock =
        try {
          it.lock()
        } catch (failure: IOException) {
          throw qaDeviceLeaseFailed("could not lock $directory: ${failure.message}", failure)
        }
      lock.use { body() }
    }
  }
}

private fun QaLeaseRecord.toLease(): QaDeviceLease = QaDeviceLease(deviceFingerprint, owner.invocationId)
