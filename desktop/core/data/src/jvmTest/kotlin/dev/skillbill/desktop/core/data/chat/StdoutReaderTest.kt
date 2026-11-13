package dev.skillbill.desktop.core.data.chat

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StdoutReaderTest {
  private fun lines(bytes: ByteArray, limit: Long?): List<String> =
    stdoutReader(ByteArrayInputStream(bytes), limit).useLines { it.toList() }

  @Test
  fun `a bounded read keeps every line including a final line without a newline`() {
    assertEquals(listOf("{\"a\":", "1}"), lines("{\"a\":\n1}".toByteArray(), limit = 64))
  }

  @Test
  fun `a bounded read fails on one oversized line and on a whole response over budget`() {
    assertFailsWith<IOException> { lines(ByteArray(10_000) { 'x'.code.toByte() }, limit = 1_000) }
    assertFailsWith<IOException> { lines("short\n".repeat(400).toByteArray(), limit = 1_000) }
  }

  @Test
  fun `a bounded read rejects invalid utf-8 while an unbounded read keeps decoding leniently`() {
    val invalid = byteArrayOf('o'.code.toByte(), 0xC3.toByte(), 'k'.code.toByte())
    assertFailsWith<IOException> { lines(invalid, limit = 64) }
    assertEquals(1, lines(invalid, limit = null).size)
  }
}
