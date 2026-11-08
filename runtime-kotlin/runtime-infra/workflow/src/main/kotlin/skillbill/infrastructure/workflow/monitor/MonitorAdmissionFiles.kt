package skillbill.infrastructure.workflow.monitor

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.workflow.repositoryStateKey
import skillbill.ports.monitor.model.MonitorTargetIdentity
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val ADMISSION_HASH_CHARS = 32
private const val MONITOR_DIRECTORY = "standalone-monitor"

internal class HeldAdmission(
  val directory: Path,
  private val lockFile: Path,
  private val channel: FileChannel,
  private val lock: FileLock,
) : AutoCloseable {
  override fun close() {
    try {
      lock.release()
    } catch (_: IOException) {
    }
    try {
      channel.close()
    } catch (_: IOException) {
    }
    MonitorAdmissionFiles.unreserve(lockFile)
  }
}

internal sealed interface AdmissionAttempt {
  data class Held(val admission: HeldAdmission) : AdmissionAttempt

  data object Contended : AdmissionAttempt
}

internal object MonitorAdmissionFiles {
  private val reservedLockFiles = ConcurrentHashMap.newKeySet<Path>()

  fun directoryFor(
    stateDir: Path,
    commonDir: Path,
    target: MonitorTargetIdentity,
  ): Path =
    stateDir
      .resolve(MONITOR_DIRECTORY)
      .resolve(repositoryStateKey(commonDir))
      .resolve(hashOf(target.targetKey))

  fun recordFile(directory: Path): Path = directory.resolve("ownership.json")

  fun tryLock(directory: Path): AdmissionAttempt {
    val realDirectory =
      try {
        Files.createDirectories(directory).toRealPath()
      } catch (failure: IOException) {
        throw ioFailure("lock the admission file in $directory", failure)
      }
    val lockFile = realDirectory.resolve("admission.lock")
    if (!reservedLockFiles.add(lockFile)) return AdmissionAttempt.Contended
    return try {
      lockReserved(realDirectory, lockFile)
    } catch (failure: IOException) {
      unreserve(lockFile)
      throw ioFailure("lock the admission file in $directory", failure)
    }
  }

  private fun lockReserved(
    directory: Path,
    lockFile: Path,
  ): AdmissionAttempt {
    val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val lock =
      try {
        channel.tryLock()
      } catch (failure: IOException) {
        channel.close()
        throw failure
      }
    return if (lock == null) {
      channel.close()
      unreserve(lockFile)
      AdmissionAttempt.Contended
    } else {
      AdmissionAttempt.Held(HeldAdmission(directory, lockFile, channel, lock))
    }
  }

  internal fun unreserve(lockFile: Path) {
    reservedLockFiles.remove(lockFile)
  }

  fun read(directory: Path): MonitorOwnershipRecord? {
    val file = recordFile(directory)
    if (!Files.exists(file)) return null
    val source =
      try {
        Files.readString(file)
      } catch (failure: IOException) {
        throw ioFailure("read $file", failure)
      }
    return StandaloneMonitorOwnershipCodec.decode(source, file.toString())
  }

  fun write(
    directory: Path,
    record: MonitorOwnershipRecord,
  ) {
    val file = recordFile(directory)
    val temporary = directory.resolve("ownership.json.tmp")
    try {
      Files.writeString(temporary, StandaloneMonitorOwnershipCodec.encode(record))
      Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (failure: IOException) {
      throw ioFailure("write $file", failure)
    }
  }

  fun archive(
    directory: Path,
    record: MonitorOwnershipRecord,
  ): Path {
    val history = directory.resolve("history").resolve(record.generation.toString())
    try {
      Files.createDirectories(history)
      val archived = read(history)
      if (archived == null) {
        write(history, record)
      } else if (archived.ownerToken != record.ownerToken || archived.generation != record.generation) {
        throw SkillBillRuntimeException(
          StandaloneMonitorFailureCode.INCOHERENT_OWNERSHIP,
          "Monitor ownership history at $history belongs to a different owner or generation.",
        )
      }
      return history
    } catch (failure: IOException) {
      throw ioFailure("archive ownership at $history", failure)
    }
  }

  private fun ioFailure(
    action: String,
    cause: IOException,
  ): SkillBillRuntimeException =
    SkillBillRuntimeException(
      StandaloneMonitorFailureCode.OWNERSHIP_IO_FAILED,
      "Standalone monitor ownership could not $action: ${cause.message}",
      cause,
    )

  private fun hashOf(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray())
      .joinToString("") { "%02x".format(it) }
      .take(ADMISSION_HASH_CHARS)
}
