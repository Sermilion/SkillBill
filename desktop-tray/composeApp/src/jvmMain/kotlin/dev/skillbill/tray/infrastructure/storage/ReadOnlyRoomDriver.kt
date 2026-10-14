package dev.skillbill.tray.infrastructure.storage

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import org.sqlite.SQLiteConfig
import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

internal class ReadOnlyRoomDriver : SQLiteDriver {
  override fun open(fileName: String): SQLiteConnection {
    val config = SQLiteConfig().apply { setReadOnly(true) }
    val jdbc = jdbcOperation { config.createConnection("jdbc:sqlite:$fileName") }
    val connection = JdbcRoomConnection(jdbc)
    try {
      connection.prepare("PRAGMA query_only = ON").use { it.step() }
      return connection
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
