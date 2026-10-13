package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopCommitSignal
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

private val desktopCommitSignalPublishLock = Any()

internal fun desktopCommitSignalPath(databasePath: Path): Path =
  databasePath.toAbsolutePath().normalize().let { absolute ->
    absolute.resolveSibling("${absolute.fileName}${DesktopCommitSignal.FILE_SUFFIX}")
  }

internal fun publishDesktopCommitSignal(
  databasePath: Path,
  connection: Connection,
  diagnostics: RuntimeDiagnostics,
) {
  val signalPath = desktopCommitSignalPath(databasePath)
  val parent = signalPath.parent ?: return
  var pending: Path? = null
  try {
    synchronized(desktopCommitSignalPublishLock) {
      Files.createDirectories(parent)
      val lockPath = signalPath.resolveSibling("${signalPath.fileName}.lock")
      FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
        channel.lock().use {
          val revisions = connection.desktopTopicRevisions()
          val payload = desktopCommitSignalPayload(revisions, diagnostics) ?: return
          val temporary = Files.createTempFile(parent, "skillbill-commit-", ".pending")
          pending = temporary
          Files.writeString(temporary, payload)
          try {
            Files.move(temporary, signalPath, ATOMIC_MOVE, REPLACE_EXISTING)
          } catch (_: AtomicMoveNotSupportedException) {
            diagnostics.warning("skillbill sqlite: desktop commit signal used a non-atomic move")
            Files.move(temporary, signalPath, REPLACE_EXISTING)
          }
          pending = null
        }
      }
    }
  } catch (failure: IOException) {
    diagnostics.warning("skillbill sqlite: desktop commit signal publication failed", failure)
  } catch (failure: SQLException) {
    diagnostics.warning("skillbill sqlite: desktop commit revision read failed", failure)
  } catch (failure: SecurityException) {
    diagnostics.warning("skillbill sqlite: desktop commit signal publication was denied", failure)
  } finally {
    pending?.let { temporary ->
      try {
        Files.deleteIfExists(temporary)
      } catch (failure: IOException) {
        diagnostics.warning("skillbill sqlite: temporary desktop commit signal cleanup failed", failure)
      } catch (failure: SecurityException) {
        diagnostics.warning("skillbill sqlite: temporary desktop commit signal cleanup was denied", failure)
      }
    }
  }
}

private fun Connection.desktopTopicRevisions(): Map<String, Long> =
  prepareStatement("SELECT topic, revision FROM ${DesktopReadSchema.REVISIONS_TABLE}").use { statement ->
    statement.executeQuery().use { rows ->
      buildMap {
        while (rows.next()) put(rows.getString(1), rows.getLong(2))
      }
    }
  }

private fun desktopCommitSignalPayload(
  revisions: Map<String, Long>,
  diagnostics: RuntimeDiagnostics,
): String? {
  if (DesktopReadSchema.TOPICS.any { topic -> revisions[topic]?.let { it >= 0 } != true }) {
    diagnostics.warning("skillbill sqlite: desktop commit signal lacked a topic revision")
    return null
  }
  val lines =
    buildList {
      add("${DesktopCommitSignal.GENERATION_LABEL}${DesktopCommitSignal.REVISION_SEPARATOR}${UUID.randomUUID()}")
      DesktopReadSchema.TOPICS.forEach { topic ->
        add("$topic${DesktopCommitSignal.REVISION_SEPARATOR}${revisions.getValue(topic)}")
      }
    }
  return lines.joinToString(separator = "\n", postfix = "\n")
}
