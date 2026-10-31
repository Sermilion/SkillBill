package skillbill.infrastructure.workflow.git.changes

import skillbill.ports.workchanges.model.WorkChangeStatus
import skillbill.ports.workchanges.model.WorkChangedFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private const val BINARY_PROBE_BYTES = 8000
private const val NEWLINE = '\n'.code
private const val NO_BYTE = -1
private const val BYTE_MASK = 0xFF
private const val READ_BUFFER_BYTES = 64 * 1024

private val untrackedLog: Logger =
  Logger.getLogger("skillbill.infrastructure.workflow.git.changes.UntrackedLineCounter")

internal fun untrackedChange(
  root: Path,
  path: String,
): WorkChangedFile? {
  val file = root.resolve(path)
  return try {
    val insertions =
      when {
        Files.isSymbolicLink(file) -> if (Files.readSymbolicLink(file).toString().isEmpty()) 0 else 1
        Files.isDirectory(file) -> null
        else -> countLines(file)
      }
    WorkChangedFile(path, null, WorkChangeStatus.UNTRACKED, insertions, insertions?.let { 0 })
  } catch (error: NoSuchFileException) {
    untrackedLog.log(
      Level.FINE,
      "seam=work_changes_untracked value_used=omitted value_expected=counted cause=vanished path=$path",
      error,
    )
    null
  }
}

private fun countLines(file: Path): Int? {
  var newlines = 0
  var offset = 0L
  var last = NO_BYTE
  val buffer = ByteArray(READ_BUFFER_BYTES)
  Files.newInputStream(file).use { input ->
    var read = input.read(buffer)
    while (read != NO_BYTE) {
      for (index in 0 until read) {
        val byte = buffer[index].toInt() and BYTE_MASK
        if (byte == 0 && offset + index < BINARY_PROBE_BYTES) return null
        if (byte == NEWLINE) newlines++
      }
      offset += read
      last = buffer[read - 1].toInt() and BYTE_MASK
      read = input.read(buffer)
    }
  }
  return if (last == NO_BYTE || last == NEWLINE) newlines else newlines + 1
}
