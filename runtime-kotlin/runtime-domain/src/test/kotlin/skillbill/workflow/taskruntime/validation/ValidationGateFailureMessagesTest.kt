package skillbill.workflow.taskruntime.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationGateFailureMessagesTest {
  @Test
  fun `long stdout excerpt including the omitted marker fits the schema limit`() {
    val stdout = "H".repeat(9_000) + "T".repeat(9_000)

    val excerpt = requireNotNull(gateStdoutExcerpt(stdout, maxChars = 8192))

    assertTrue(excerpt.length <= 8192, "excerpt length ${excerpt.length} exceeds 8192")
    assertTrue(excerpt.startsWith("H"))
    assertTrue(excerpt.endsWith("T"))
    val omitted = Regex("""…\[(\d+) chars omitted]…""").find(excerpt)?.groupValues?.get(1)?.toInt()
    val kept = excerpt.count { it == 'H' || it == 'T' }
    assertEquals(stdout.length, kept + requireNotNull(omitted))
  }

  @Test
  fun `stdout just over the limit still fits once the marker is added`() {
    for (length in 8193..8230) {
      val excerpt = requireNotNull(gateStdoutExcerpt("x".repeat(length), maxChars = 8192))
      assertTrue(excerpt.length <= 8192, "stdout length $length gave excerpt length ${excerpt.length}")
    }
  }

  @Test
  fun `stdout within the limit is returned trimmed and blank stdout yields null`() {
    assertEquals("ok", gateStdoutExcerpt("  ok \n", maxChars = 8192))
    assertNull(gateStdoutExcerpt(" \n ", maxChars = 8192))
  }

  @Test
  fun `limit smaller than the marker truncates to the limit`() {
    assertEquals("abcde", gateStdoutExcerpt("abcdefghijklmnopqrstuvwxyz", maxChars = 5))
  }
}
