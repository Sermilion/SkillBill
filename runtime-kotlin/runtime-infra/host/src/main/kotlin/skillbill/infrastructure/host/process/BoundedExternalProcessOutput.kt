package skillbill.infrastructure.host.process

import java.io.BufferedReader
import java.io.InputStream
import java.nio.file.Path

internal const val INSTALLER_PROCESS_OUTPUT_CAP_BYTES: Int = 1024 * 1024

internal const val INSTALLER_OUTPUT_TRUNCATION_SENTINEL: String =
  "\n...[installer output truncated at $INSTALLER_PROCESS_OUTPUT_CAP_BYTES bytes]..."

data class BoundedOutputLine(
  val text: String,
  val truncated: Boolean,
)

sealed interface BoundedExternalProcessOutput {
  data class Captured(
    val capBytes: Long? = INSTALLER_PROCESS_OUTPUT_CAP_BYTES.toLong(),
  ) : BoundedExternalProcessOutput

  data class Lines(
    val maxLineBytes: Int,
    val shouldStop: () -> Boolean,
    val onLine: (BoundedOutputLine) -> Unit,
  ) : BoundedExternalProcessOutput

  data class RedirectToFile(
    val path: Path,
  ) : BoundedExternalProcessOutput
}

internal fun readCappedOutput(
  stream: InputStream,
  cap: Long?,
  output: StringBuilder,
): Boolean {
  if (cap == null) {
    output.append(stream.bufferedReader().readText())
    return false
  }
  val buffer = ByteArray(OUTPUT_BUFFER_BYTES)
  var capturedBytes = 0L
  var truncated = false
  var done = false
  while (!done) {
    val count = stream.read(buffer)
    if (count < 0) {
      done = true
    } else if (capturedBytes + count > cap) {
      val remaining = cap - capturedBytes
      if (remaining > 0) {
        output.append(String(buffer, 0, remaining.toInt(), Charsets.UTF_8))
        capturedBytes += remaining
      }
      truncated = true
    } else {
      output.append(String(buffer, 0, count, Charsets.UTF_8))
      capturedBytes += count
    }
  }
  return truncated
}

internal fun readBoundedLines(
  stream: InputStream,
  mode: BoundedExternalProcessOutput.Lines,
): Boolean {
  stream.bufferedReader().use { reader ->
    while (true) {
      val line = reader.readBoundedLine(mode.maxLineBytes) ?: return false
      mode.onLine(line)
      if (mode.shouldStop()) return true
    }
  }
}

internal fun BufferedReader.readBoundedLine(maxBytes: Int): BoundedOutputLine? {
  val line = StringBuilder()
  var bytes = 0
  var sawContent = false
  var truncated = false
  var complete = false
  while (!complete) {
    val next = read()
    when {
      next == -1 -> complete = true
      next.toChar() == '\n' -> {
        sawContent = true
        complete = true
      }
      else -> {
        sawContent = true
        val char = next.toChar()
        val charBytes = char.toString().toByteArray().size
        if (bytes + charBytes > maxBytes) {
          truncated = true
          complete = true
        } else {
          line.append(char)
          bytes += charBytes
        }
      }
    }
  }
  return if (sawContent) BoundedOutputLine(line.toString(), truncated = truncated) else null
}

private const val OUTPUT_BUFFER_BYTES = 8192
