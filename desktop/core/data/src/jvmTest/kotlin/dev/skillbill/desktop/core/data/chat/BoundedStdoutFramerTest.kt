package dev.skillbill.desktop.core.data.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedStdoutFramerTest {
  @Test
  fun `framer accepts the exact byte line limit and rejects the next input byte`() {
    val accepted = BoundedStdoutFramer()
    val lines = mutableListOf<String>()
    val exact = ByteArray(BoundedStdoutFramer.MAX_LINE_BYTES + 1) { if (it == BoundedStdoutFramer.MAX_LINE_BYTES) '\n'.code.toByte() else 'a'.code.toByte() }
    assertNull(accepted.accept(exact, exact.size) { line ->
      lines.add(line)
      true
    })
    assertEquals(BoundedStdoutFramer.MAX_LINE_BYTES, lines.single().length)

    val rejected = BoundedStdoutFramer()
    val oversized = ByteArray(BoundedStdoutFramer.MAX_LINE_BYTES + 1) { 'a'.code.toByte() }
    assertEquals(BoundedProtocolInput.Kind.LineTooLarge, rejected.accept(oversized, oversized.size) { true })
  }

  @Test
  fun `framer counts UTF-8 bytes and aggregate response bytes before decoding`() {
    val multibyte = BoundedStdoutFramer()
    val oversizedLine = "é".repeat(BoundedStdoutFramer.MAX_LINE_BYTES / 2 + 1).toByteArray()
    assertEquals(BoundedProtocolInput.Kind.LineTooLarge, multibyte.accept(oversizedLine, oversizedLine.size) { true })

    val aggregate = BoundedStdoutFramer()
    aggregate.beginResponseBudget()
    val traffic = ByteArray(BoundedStdoutFramer.MAX_RESPONSE_BYTES + 1) { '\n'.code.toByte() }
    var failure: BoundedProtocolInput.Kind? = null
    var offset = 0
    while (offset < traffic.size && failure == null) {
      val count = minOf(4096, traffic.size - offset)
      failure = aggregate.accept(traffic.copyOfRange(offset, offset + count), count) { true }
      offset += count
    }
    assertEquals(BoundedProtocolInput.Kind.ResponseTooLarge, failure)
  }

  @Test
  fun `a full inbound queue becomes a terminal size outcome`() {
    val framer = BoundedStdoutFramer()
    val line = byteArrayOf('x'.code.toByte(), '\n'.code.toByte())

    assertEquals(BoundedProtocolInput.Kind.QueueFull, framer.accept(line, line.size) { false })
  }
}
