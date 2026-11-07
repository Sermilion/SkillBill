package dev.skillbill.desktop.core.data.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class BoundedStdoutFramer {
  private val frame = ByteArray(MAX_LINE_BYTES)
  private var frameSize = 0
  private var responseBytes = 0
  private var responseBudgetOpen = false

  @Synchronized
  fun beginResponseBudget() {
    responseBytes = 0
    responseBudgetOpen = true
  }

  @Synchronized
  fun endResponseBudget() {
    responseBudgetOpen = false
  }

  @Synchronized
  fun accept(bytes: ByteArray, count: Int, onLine: (String) -> Boolean): BoundedProtocolInput.Kind? {
    for (index in 0 until count) {
      val byte = bytes[index]
      if (responseBudgetOpen && ++responseBytes > MAX_RESPONSE_BYTES) {
        return BoundedProtocolInput.Kind.ResponseTooLarge
      }
      if (byte == '\n'.code.toByte()) {
        val length = if (frameSize > 0 && frame[frameSize - 1] == '\r'.code.toByte()) frameSize - 1 else frameSize
        val decoder = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        val line = try {
          decoder.decode(ByteBuffer.wrap(frame, 0, length)).toString()
        } catch (_: Exception) {
          return BoundedProtocolInput.Kind.InvalidUtf8
        }
        frameSize = 0
        if (!onLine(line)) return BoundedProtocolInput.Kind.QueueFull
      } else {
        if (frameSize == MAX_LINE_BYTES) return BoundedProtocolInput.Kind.LineTooLarge
        frame[frameSize++] = byte
      }
    }
    return null
  }

  companion object {
    const val MAX_LINE_BYTES = 256 * 1024
    const val MAX_RESPONSE_BYTES = 1024 * 1024
  }
}
