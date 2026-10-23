package skillbill.infrastructure.host.blockincident

import me.tatarka.inject.annotations.Inject
import skillbill.ports.blockincident.ProductReportDeliveryOwnershipPort
import skillbill.ports.blockincident.model.ProductReportDeliveryOwnership
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.FileLockInterruptionException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Inject
class FileProductReportDeliveryOwnership : ProductReportDeliveryOwnershipPort {
  override fun acquire(
    databasePath: Path,
    reportId: String,
  ): ProductReportDeliveryOwnership {
    return try {
      acquireOwned(databasePath, reportId)
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      throw failure
    } catch (failure: FileLockInterruptionException) {
      Thread.currentThread().interrupt()
      throw failure
    } catch (failure: IOException) {
      if (Thread.currentThread().isInterrupted) {
        Thread.currentThread().interrupt()
        throw failure
      }
      ProductReportDeliveryOwnership.Failed(failure)
    } catch (failure: SecurityException) {
      if (Thread.currentThread().isInterrupted) throw failure
      ProductReportDeliveryOwnership.Failed(IOException("Could not acquire product report ownership.", failure))
    }
  }

  private fun acquireOwned(
    databasePath: Path,
    reportId: String,
  ): ProductReportDeliveryOwnership {
    val canonicalDb = databasePath.toAbsolutePath().normalize().toRealPath()
    val key = "$canonicalDb\u0000$reportId"
    val directory = canonicalDb.parent.resolve(".skill-bill-report-delivery")
    Files.createDirectories(directory)
    val lockPath = directory.toRealPath().resolve(hash(key) + ".lock")
    if (!reserved.add(lockPath)) return ProductReportDeliveryOwnership.Contended
    val channel =
      try {
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      } catch (failure: IOException) {
        reserved.remove(lockPath)
        throw failure
      }
    val lock =
      try {
        channel.tryLock()
      } catch (_: OverlappingFileLockException) {
        null
      } catch (failure: IOException) {
        channel.close()
        reserved.remove(lockPath)
        throw failure
      }
    if (lock == null) {
      channel.close()
      reserved.remove(lockPath)
      return ProductReportDeliveryOwnership.Contended
    }
    return HeldReportLock(lockPath, channel, lock)
  }

  private class HeldReportLock(
    private val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
  ) : ProductReportDeliveryOwnership.Held {
    override fun close() {
      try {
        lock.release()
      } finally {
        try {
          channel.close()
        } finally {
          reserved.remove(path)
        }
      }
    }
  }

  private fun hash(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray())
      .joinToString("") { "%02x".format(it) }

  private companion object {
    val reserved = ConcurrentHashMap.newKeySet<Path>()
  }
}
