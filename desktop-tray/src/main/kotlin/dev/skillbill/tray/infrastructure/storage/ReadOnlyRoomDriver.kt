package dev.skillbill.tray.infrastructure.storage

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal class ReadOnlyRoomDriver : SQLiteDriver {
  private val driver = BundledSQLiteDriver()

  override fun open(fileName: String): SQLiteConnection =
    driver.open(fileName, SQLITE_OPEN_READONLY or SQLITE_OPEN_FULLMUTEX).also { connection ->
      try {
        connection.prepare("PRAGMA query_only = ON").use { it.step() }
      } catch (failure: Throwable) {
        try {
          connection.close()
        } catch (closeFailure: Throwable) {
          failure.addSuppressed(closeFailure)
          RuntimeDiagnostics.record("room_driver_connection_close_failed")
        }
        throw failure
      }
    }
}

internal fun openWorkRoomDatabase(path: Path): WorkRoomDatabase =
  Room.databaseBuilder<WorkRoomDatabase>(path.toAbsolutePath().normalize().toString())
    .setDriver(ReadOnlyRoomDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .build()

internal fun storeFileIdentity(path: Path): String {
  val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
  return "${attributes.fileKey()}:${attributes.creationTime()}"
}
